const JSON_HEADERS = {
  "Content-Type": "application/json; charset=utf-8",
};

export default {
  async fetch(request, env) {
    try {
      const url = new URL(request.url);

      if (request.method === "OPTIONS") {
        return handleOptions(request, env);
      }

      if (request.method === "GET" && url.pathname === "/health") {
        return jsonResponse(
          {
            ok: true,
            service: "canyon-submission-api",
            time: new Date().toISOString(),
          },
          200,
          request,
          env,
        );
      }

      if (request.method === "POST" && url.pathname === "/submit-canyon") {
        return await handleSubmitCanyon(request, env);
      }

      return jsonResponse({ ok: false, error: "Not found" }, 404, request, env);
    } catch (error) {
      console.error("Unhandled worker error", serializeError(error));
      return jsonResponse({ ok: false, error: "Internal server error" }, 500, request, env);
    }
  },
};

async function handleSubmitCanyon(request, env) {
  const missingEnv = missingRuntimeConfig(env);
  if (missingEnv.length > 0) {
    console.error("Missing worker runtime configuration", { missingEnv });
    return jsonResponse(
      {
        ok: false,
        error: "Worker is not configured",
      },
      500,
      request,
      env,
    );
  }

  if (!isAllowedOrigin(request, env)) {
    return jsonResponse({ ok: false, error: "Origin not allowed" }, 403, request, env);
  }

  let payload;
  try {
    payload = await request.json();
  } catch {
    return jsonResponse({ ok: false, error: "Invalid JSON body" }, 400, request, env);
  }

  const normalized = normalizePayload(payload);
  const validationErrors = validatePayload(normalized);

  if (validationErrors.length > 0) {
    return jsonResponse(
      {
        ok: false,
        error: "Validation failed",
        details: validationErrors,
      },
      400,
      request,
      env,
    );
  }

  const githubResponse = await createGitHubIssue({
    env,
    title: buildIssueTitle(normalized),
    body: buildIssueBody(normalized, request),
    labels: buildLabels(normalized),
  });

  if (!githubResponse.ok) {
    console.error("GitHub issue creation failed", {
      status: githubResponse.status,
      body: await githubResponse.text(),
    });

    return jsonResponse(
      {
        ok: false,
        error: "GitHub issue creation failed",
      },
      502,
      request,
      env,
    );
  }

  const createdIssue = await githubResponse.json();

  return jsonResponse(
    {
      ok: true,
      issueNumber: createdIssue.number,
      issueUrl: createdIssue.html_url,
      title: createdIssue.title,
    },
    201,
    request,
    env,
  );
}

function handleOptions(request, env) {
  const origin = request.headers.get("Origin");
  if (!origin || !isAllowedOrigin(request, env)) {
    return new Response(null, { status: 403 });
  }

  return new Response(null, {
    status: 204,
    headers: corsHeaders(origin),
  });
}

function missingRuntimeConfig(env) {
  return ["GITHUB_OWNER", "GITHUB_REPO", "ALLOWED_ORIGINS", "GITHUB_TOKEN"].filter(
    (key) => typeof env[key] !== "string" || env[key].trim() === "",
  );
}

function isAllowedOrigin(request, env) {
  const origin = request.headers.get("Origin");
  if (!origin) {
    return false;
  }

  return parseCsv(env.ALLOWED_ORIGINS).includes(origin);
}

function corsHeaders(origin) {
  return {
    "Access-Control-Allow-Origin": origin,
    "Access-Control-Allow-Methods": "POST, OPTIONS, GET",
    "Access-Control-Allow-Headers": "Content-Type",
    "Access-Control-Max-Age": "86400",
    Vary: "Origin",
  };
}

function jsonResponse(data, status, request, env) {
  const headers = new Headers(JSON_HEADERS);
  const origin = request.headers.get("Origin");

  if (origin && typeof env.ALLOWED_ORIGINS === "string" && parseCsv(env.ALLOWED_ORIGINS).includes(origin)) {
    for (const [key, value] of Object.entries(corsHeaders(origin))) {
      headers.set(key, value);
    }
  }

  return new Response(JSON.stringify(data, null, 2), {
    status,
    headers,
  });
}

function normalizePayload(input) {
  return {
    name: sanitizeText(input.name, 120),
    country: sanitizeText(input.country, 80),
    region: sanitizeText(input.region, 80),
    department: sanitizeText(input.department, 80),
    municipality: sanitizeText(input.municipality, 120),
    entryLat: sanitizeCoordinate(input.entryLat),
    entryLng: sanitizeCoordinate(input.entryLng),
    exitLat: sanitizeCoordinate(input.exitLat),
    exitLng: sanitizeCoordinate(input.exitLng),
    parkingLat: sanitizeCoordinate(input.parkingLat),
    parkingLng: sanitizeCoordinate(input.parkingLng),
    description: sanitizeText(input.description, 4000),
    approach: sanitizeText(input.approach, 3000),
    descent: sanitizeText(input.descent, 3000),
    returnRoute: sanitizeText(input.returnRoute, 3000),
    sources: sanitizeText(input.sources, 3000),
    submitterPseudo: sanitizeText(input.submitterPseudo, 80),
    sourceContext: sanitizeSourceContext(input.sourceContext),
    honeypot: typeof input.honeypot === "string" ? input.honeypot.trim() : "",
    publicConsent: input.publicConsent === true,
  };
}

function validatePayload(payload) {
  const errors = [];

  if (payload.honeypot) {
    errors.push("Spam detected");
  }
  if (!payload.publicConsent) {
    errors.push("Public consent is required");
  }
  if (!payload.name) {
    errors.push("Name is required");
  }
  if (!payload.country) {
    errors.push("Country is required");
  }
  if (!payload.description) {
    errors.push("Description is required");
  }
  if (!payload.sources) {
    errors.push("At least one source is required");
  }

  validateCoordinatePair(errors, "entry", payload.entryLat, payload.entryLng);
  validateCoordinatePair(errors, "exit", payload.exitLat, payload.exitLng);
  validateCoordinatePair(errors, "parking", payload.parkingLat, payload.parkingLng);

  return errors;
}

function validateCoordinatePair(errors, label, lat, lng) {
  const hasLat = lat !== null;
  const hasLng = lng !== null;

  if (hasLat !== hasLng) {
    errors.push(`${label} latitude/longitude must be provided together`);
    return;
  }
  if (!hasLat) {
    return;
  }
  if (lat < -90 || lat > 90) {
    errors.push(`${label} latitude is invalid`);
  }
  if (lng < -180 || lng > 180) {
    errors.push(`${label} longitude is invalid`);
  }
}

function sanitizeText(value, maxLength) {
  if (typeof value !== "string") {
    return "";
  }
  return value.replace(/\r\n/g, "\n").trim().slice(0, maxLength);
}

function sanitizeCoordinate(value) {
  if (value === null || value === undefined || value === "") {
    return null;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function sanitizeSourceContext(value) {
  const normalized = typeof value === "string" ? value.trim().toLowerCase() : "";
  return normalized === "app" ? "app" : "web";
}

function buildIssueTitle(payload) {
  const locationParts = [payload.department, payload.region, payload.country].filter(Boolean);
  const location = locationParts.slice(0, 2).join(" - ");
  return location
    ? `[Nouveau canyon] ${payload.name} - ${location}`
    : `[Nouveau canyon] ${payload.name}`;
}

function buildIssueBody(payload, request) {
  const submittedAt = new Date().toISOString();
  const remoteIpCountry = request.headers.get("CF-IPCountry") || "unknown";

  return [
    "## Identite",
    "",
    `- Nom: ${valueOrFallback(payload.name)}`,
    `- Pseudo: ${valueOrFallback(payload.submitterPseudo)}`,
    `- Pays: ${valueOrFallback(payload.country)}`,
    `- Region: ${valueOrFallback(payload.region)}`,
    `- Departement: ${valueOrFallback(payload.department)}`,
    `- Commune: ${valueOrFallback(payload.municipality)}`,
    "",
    "## Coordonnees",
    "",
    coordinateLine("Entree", payload.entryLat, payload.entryLng),
    coordinateLine("Sortie", payload.exitLat, payload.exitLng),
    coordinateLine("Parking", payload.parkingLat, payload.parkingLng),
    "",
    "## Description",
    "",
    valueOrFallbackBlock(payload.description),
    "",
    "## Approche",
    "",
    valueOrFallbackBlock(payload.approach),
    "",
    "## Descente",
    "",
    valueOrFallbackBlock(payload.descent),
    "",
    "## Retour",
    "",
    valueOrFallbackBlock(payload.returnRoute),
    "",
    "## Sources",
    "",
    valueOrFallbackBlock(payload.sources),
    "",
    "## Meta",
    "",
    `- Source: ${payload.sourceContext}`,
    "- Consentement public: oui",
    `- Soumis le: ${submittedAt}`,
    `- Pays IP Cloudflare: ${remoteIpCountry}`,
  ].join("\n");
}

function coordinateLine(label, lat, lng) {
  if (lat === null || lng === null) {
    return `- ${label}: non renseigne`;
  }
  return `- ${label}: ${lat}, ${lng}`;
}

function valueOrFallback(value) {
  return value || "non renseigne";
}

function valueOrFallbackBlock(value) {
  return value || "Non renseigne.";
}

function buildLabels() {
  return ["new-canyon"];
}

async function createGitHubIssue({ env, title, body, labels }) {
  const apiUrl = `https://api.github.com/repos/${env.GITHUB_OWNER}/${env.GITHUB_REPO}/issues`;

  return await fetch(apiUrl, {
    method: "POST",
    headers: {
      Accept: "application/vnd.github+json",
      Authorization: `Bearer ${env.GITHUB_TOKEN}`,
      "Content-Type": "application/json",
      "User-Agent": "descente-canyon-canyon-submission-worker",
      "X-GitHub-Api-Version": "2022-11-28",
    },
    body: JSON.stringify({
      title,
      body,
      labels,
    }),
  });
}

function parseCsv(value) {
  if (typeof value !== "string") {
    return [];
  }
  return value
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
}

function serializeError(error) {
  if (!error || typeof error !== "object") {
    return { message: String(error) };
  }

  return {
    name: error.name || "Error",
    message: error.message || "Unknown error",
    stack: error.stack || "",
  };
}
