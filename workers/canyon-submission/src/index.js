const JSON_HEADERS = {
  "Content-Type": "application/json; charset=utf-8",
};

const GEO_POINT_TYPES = {
  PARKING_AMONT: "Parking amont",
  PARKING_AVAL: "Parking aval",
  ENTREE: "Entree",
  SORTIE: "Sortie",
  POINT_REMARQUABLE: "Point remarquable",
  ECHAPPATOIRE: "Echappatoire",
  UNKNOWN: "Point GPS",
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
    labels: buildLabels(),
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
    fullName: sanitizeText(input.fullName, 160),
    country: sanitizeText(input.country, 80),
    region: sanitizeText(input.region, 80),
    department: sanitizeText(input.department, 80),
    municipality: sanitizeText(input.municipality, 120),
    communes: sanitizeText(input.communes, 200),
    massif: sanitizeText(input.massif, 120),
    basin: sanitizeText(input.basin, 120),
    watercourse: sanitizeText(input.watercourse, 120),
    ratingVerticality: sanitizeRatingPart(input.ratingVerticality),
    ratingAquatic: sanitizeRatingPart(input.ratingAquatic),
    ratingEngagement: sanitizeRatingPart(input.ratingEngagement),
    altitudeStart: sanitizeInteger(input.altitudeStart),
    elevation: sanitizeInteger(input.elevation),
    length: sanitizeInteger(input.length),
    maxWaterfall: sanitizeInteger(input.maxWaterfall),
    minRope: sanitizeInteger(input.minRope),
    interest: sanitizeInterest(input.interest),
    approachTime: sanitizeText(input.approachTime, 80),
    descentTime: sanitizeText(input.descentTime, 80),
    returnTime: sanitizeText(input.returnTime, 80),
    hasShuttle: input.hasShuttle === true,
    description: sanitizeText(input.description, 4000),
    accessDownstream: sanitizeText(input.accessDownstream, 3000),
    accessUpstream: sanitizeText(input.accessUpstream, 3000),
    approach: sanitizeText(input.approach, 3000),
    descent: sanitizeText(input.descent, 3000),
    returnRoute: sanitizeText(input.returnRoute, 3000),
    period: sanitizeText(input.period, 160),
    geology: sanitizeText(input.geology, 3000),
    history: sanitizeText(input.history, 3000),
    remarks: sanitizeText(input.remarks, 3000),
    sources: sanitizeText(input.sources, 3000),
    bibliography: sanitizeText(input.bibliography, 3000),
    regulations: sanitizeText(input.regulations, 3000),
    geoPoints: sanitizeGeoPoints(input.geoPoints),
    gpxTrace: sanitizeGpxTrace(input.gpxTrace),
    submitterPseudo: sanitizeText(input.submitterPseudo, 80),
    sourceContext: sanitizeSourceContext(input.sourceContext),
    honeypot: typeof input.honeypot === "string" ? input.honeypot.trim() : "",
    publicConsent: input.publicConsent === true,
  };
}

function sanitizeGeoPoints(value) {
  if (!Array.isArray(value)) {
    return [];
  }

  return value
    .map((item) => normalizeGeoPoint(item))
    .filter((point) => point.hasAnyUserData)
    .map(({ hasAnyUserData, ...point }) => point);
}

function normalizeGeoPoint(item) {
  const latitude = sanitizeCoordinate(item?.latitude ?? item?.lat);
  const longitude = sanitizeCoordinate(item?.longitude ?? item?.lng);
  const title = sanitizeText(item?.title ?? item?.label, 120);
  const remark = sanitizeText(item?.remark ?? item?.comment, 500);

  return {
    type: sanitizeGeoPointType(item?.type),
    latitude,
    longitude,
    title,
    remark,
    hasAnyUserData: latitude !== null || longitude !== null || Boolean(title) || Boolean(remark),
  };
}

function sanitizeGeoPointType(value) {
  if (typeof value !== "string") {
    return "UNKNOWN";
  }

  const normalized = value
    .trim()
    .toUpperCase()
    .replace(/[\s-]+/g, "_")
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "");

  switch (normalized) {
    case "PARKING_AMONT":
    case "UPSTREAM_PARKING":
      return "PARKING_AMONT";
    case "PARKING_AVAL":
    case "PARKING":
    case "DOWNSTREAM_PARKING":
      return "PARKING_AVAL";
    case "ENTREE":
    case "DEPART":
    case "ENTRY":
      return "ENTREE";
    case "SORTIE":
    case "ARRIVEE":
    case "EXIT":
      return "SORTIE";
    case "POINT_REMARQUABLE":
    case "REMARKABLE_POINT":
    case "POINT_EXTERNE":
    case "POINT_INTERNE":
      return "POINT_REMARQUABLE";
    case "ECHAPPATOIRE":
    case "ESCAPE":
      return "ECHAPPATOIRE";
    default:
      return "UNKNOWN";
  }
}

function sanitizeGpxTrace(value) {
  if (!value || typeof value !== "object") {
    return null;
  }

  const fileName = sanitizeText(value.fileName, 180);
  if (!fileName) {
    return null;
  }

  return {
    fileName,
    pointCount: sanitizeInteger(value.pointCount) ?? 0,
    segmentCount: sanitizeInteger(value.segmentCount) ?? 0,
    waypointCount: sanitizeInteger(value.waypointCount) ?? 0,
    bbox: sanitizeBbox(value.bbox),
    rawContent: sanitizeText(value.rawContent, 16000),
    isTruncated: value.isTruncated === true,
  };
}

function sanitizeBbox(value) {
  if (!value || typeof value !== "object") {
    return null;
  }

  const minLat = sanitizeCoordinate(value.minLat);
  const minLng = sanitizeCoordinate(value.minLng);
  const maxLat = sanitizeCoordinate(value.maxLat);
  const maxLng = sanitizeCoordinate(value.maxLng);

  if ([minLat, minLng, maxLat, maxLng].some((item) => item === null)) {
    return null;
  }

  return { minLat, minLng, maxLat, maxLng };
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
  if (payload.geoPoints.length > 24) {
    errors.push("Too many geo points");
  }

  validateRating(errors, payload);
  validateNonNegativeInteger(errors, "altitudeStart", payload.altitudeStart);
  validateNonNegativeInteger(errors, "elevation", payload.elevation);
  validateNonNegativeInteger(errors, "length", payload.length);
  validateNonNegativeInteger(errors, "maxWaterfall", payload.maxWaterfall);
  validateNonNegativeInteger(errors, "minRope", payload.minRope);
  validateInterest(errors, payload.interest);

  payload.geoPoints.forEach((point, index) => validateGeoPoint(errors, point, index));
  validateGpxTrace(errors, payload.gpxTrace);

  return errors;
}

function validateRating(errors, payload) {
  const parts = [payload.ratingVerticality, payload.ratingAquatic, payload.ratingEngagement];
  const filledCount = parts.filter((value) => value !== null).length;

  if (filledCount === 0) {
    return;
  }
  if (filledCount !== 3) {
    errors.push("ratingVerticality, ratingAquatic and ratingEngagement must all be provided together");
    return;
  }

  parts.forEach((value, index) => {
    if (value < 1 || value > 9) {
      errors.push(["ratingVerticality", "ratingAquatic", "ratingEngagement"][index] + " must be between 1 and 9");
    }
  });
}

function validateNonNegativeInteger(errors, label, value) {
  if (value === null) {
    return;
  }
  if (!Number.isInteger(value) || value < 0) {
    errors.push(`${label} must be a positive integer`);
  }
}

function validateInterest(errors, value) {
  if (value === null) {
    return;
  }
  if (!Number.isInteger(value) || value < 0 || value > 4) {
    errors.push("interest must be an integer between 0 and 4");
  }
}

function validateGeoPoint(errors, point, index) {
  const prefix = `geoPoint #${index + 1}`;

  if (point.latitude === null || point.longitude === null) {
    errors.push(`${prefix} must include latitude and longitude`);
    return;
  }
  if (point.latitude < -90 || point.latitude > 90) {
    errors.push(`${prefix} latitude is invalid`);
  }
  if (point.longitude < -180 || point.longitude > 180) {
    errors.push(`${prefix} longitude is invalid`);
  }
}

function validateGpxTrace(errors, gpxTrace) {
  if (!gpxTrace) {
    return;
  }
  if (gpxTrace.pointCount <= 0) {
    errors.push("gpxTrace must contain at least one point");
  }
  if (gpxTrace.segmentCount < 0 || gpxTrace.waypointCount < 0) {
    errors.push("gpxTrace counts are invalid");
  }
  if (gpxTrace.bbox) {
    validateCoordinateBounds(errors, "gpxTrace bbox", gpxTrace.bbox.minLat, gpxTrace.bbox.minLng);
    validateCoordinateBounds(errors, "gpxTrace bbox", gpxTrace.bbox.maxLat, gpxTrace.bbox.maxLng);
  }
}

function validateCoordinateBounds(errors, label, lat, lng) {
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

function sanitizeInteger(value) {
  if (value === null || value === undefined || value === "") {
    return null;
  }
  const parsed = Number(value);
  return Number.isInteger(parsed) ? parsed : null;
}

function sanitizeRatingPart(value) {
  return sanitizeInteger(value);
}

function sanitizeInterest(value) {
  return sanitizeInteger(value);
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
    bulletLine("Nom", payload.name, true),
    bulletLine("Nom complet", payload.fullName),
    bulletLine("Pseudo", payload.submitterPseudo),
    "",
    "## Localisation",
    "",
    bulletLine("Pays", payload.country, true),
    bulletLine("Region", payload.region),
    bulletLine("Departement", payload.department),
    bulletLine("Commune", payload.municipality),
    bulletLine("Communes", payload.communes),
    bulletLine("Massif", payload.massif),
    bulletLine("Bassin", payload.basin),
    bulletLine("Cours d'eau", payload.watercourse),
    "",
    "## Caracteristiques",
    "",
    bulletLine("Cotation", formatCotation(payload)),
    bulletLine("Interet", formatInterest(payload.interest)),
    bulletLine("Altitude depart", formatMetric(payload.altitudeStart, "m")),
    bulletLine("Denivele", formatMetric(payload.elevation, "m")),
    bulletLine("Longueur", formatMetric(payload.length, "m")),
    bulletLine("Cascade max", formatMetric(payload.maxWaterfall, "m")),
    bulletLine("Corde min", formatMetric(payload.minRope, "m")),
    bulletLine("Temps approche", payload.approachTime),
    bulletLine("Temps descente", payload.descentTime),
    bulletLine("Temps retour", payload.returnTime),
    `- Navette: ${payload.hasShuttle ? "oui" : "non"}`,
    bulletLine("Periode", payload.period),
    "",
    sectionBlock("## Description", payload.description),
    "",
    sectionBlock("## Acces aval", payload.accessDownstream),
    "",
    sectionBlock("## Acces amont", payload.accessUpstream),
    "",
    sectionBlock("## Approche", payload.approach),
    "",
    sectionBlock("## Descente", payload.descent),
    "",
    sectionBlock("## Retour", payload.returnRoute),
    "",
    sectionBlock("## Geologie", payload.geology),
    "",
    sectionBlock("## Historique", payload.history),
    "",
    sectionBlock("## Remarques", payload.remarks),
    "",
    "## Points GPS",
    "",
    formatGeoPoints(payload.geoPoints),
    "",
    "## Trace GPX",
    "",
    formatGpxTrace(payload.gpxTrace),
    "",
    sectionBlock("## Sources", payload.sources),
    "",
    sectionBlock("## Bibliographie", payload.bibliography),
    "",
    sectionBlock("## Reglementation", payload.regulations),
    "",
    "## Meta",
    "",
    bulletLine("Source", payload.sourceContext, true),
    "- Consentement public: oui",
    `- Soumis le: ${submittedAt}`,
    `- Pays IP Cloudflare: ${remoteIpCountry}`,
  ]
    .filter((line) => line !== null)
    .join("\n");
}

function bulletLine(label, value, required = false) {
  if (!value) {
    return required ? `- ${label}: non renseigne` : null;
  }
  return `- ${label}: ${value}`;
}

function sectionBlock(title, value) {
  return [title, "", valueOrFallbackBlock(value)].join("\n");
}

function formatCotation(payload) {
  if (payload.ratingVerticality === null || payload.ratingAquatic === null || payload.ratingEngagement === null) {
    return "";
  }
  return `V${payload.ratingVerticality} A${payload.ratingAquatic} E${payload.ratingEngagement}`;
}

function formatGeoPoints(points) {
  if (points.length === 0) {
    return "Aucun point GPS renseigne.";
  }

  return points
    .map((point) => {
      const extras = [];
      if (point.title) {
        extras.push(`titre: ${point.title}`);
      }
      if (point.remark) {
        extras.push(`remarque: ${point.remark}`);
      }

      const suffix = extras.length > 0 ? ` (${extras.join("; ")})` : "";
      return `- ${humanizeGeoPointType(point.type)}: ${formatCoordinate(point.latitude)}, ${formatCoordinate(point.longitude)}${suffix}`;
    })
    .join("\n");
}

function formatGpxTrace(gpxTrace) {
  if (!gpxTrace) {
    return "Aucune trace GPX fournie.";
  }

  const lines = [
    `- Fichier: ${gpxTrace.fileName}`,
    `- Segments: ${gpxTrace.segmentCount}`,
    `- Points: ${gpxTrace.pointCount}`,
    `- Waypoints: ${gpxTrace.waypointCount}`,
  ];

  if (gpxTrace.bbox) {
    lines.push(
      `- Bbox: ${formatCoordinate(gpxTrace.bbox.minLat)}, ${formatCoordinate(gpxTrace.bbox.minLng)} -> ${formatCoordinate(gpxTrace.bbox.maxLat)}, ${formatCoordinate(gpxTrace.bbox.maxLng)}`,
    );
  }

  if (gpxTrace.rawContent) {
    lines.push("");
    lines.push("<details>");
    lines.push("<summary>Contenu GPX</summary>");
    lines.push("");
    lines.push("```xml");
    lines.push(gpxTrace.rawContent);
    lines.push("```");
    if (gpxTrace.isTruncated) {
      lines.push("");
      lines.push("Le contenu GPX a ete tronque pour tenir dans l'issue.");
    }
    lines.push("</details>");
  } else if (gpxTrace.isTruncated) {
    lines.push("- Contenu GPX non integre dans l'issue car le fichier est trop volumineux.");
  }

  return lines.join("\n");
}

function humanizeGeoPointType(type) {
  return GEO_POINT_TYPES[type] || GEO_POINT_TYPES.UNKNOWN;
}

function formatMetric(value, unit) {
  if (value === null) {
    return "";
  }
  return `${value} ${unit}`;
}

function formatInterest(value) {
  if (value === null) {
    return "";
  }
  return `${value}/4`;
}

function formatCoordinate(value) {
  return Number(value).toFixed(6).replace(/0+$/g, "").replace(/\.$/, "");
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
