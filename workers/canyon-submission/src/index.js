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

  let submissionResult;
  try {
    submissionResult = await createSubmissionArtifacts({ payload: normalized, request, env });
  } catch (error) {
    console.error("GitHub submission flow failed", serializeError(error));
    return jsonResponse(
      {
        ok: false,
        error: error.message || "GitHub submission flow failed",
      },
      502,
      request,
      env,
    );
  }

  return jsonResponse(
    {
      ok: true,
      pullRequestNumber: submissionResult.pullRequest.number,
      pullRequestUrl: submissionResult.pullRequest.html_url,
      issueNumber: submissionResult.issue?.number ?? null,
      issueUrl: submissionResult.issue?.html_url ?? null,
      title: submissionResult.pullRequest.title,
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
    descenteCanyonId: sanitizeInteger(input.descenteCanyonId ?? input.dcId),
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
  const isDcOverlay = payload.descenteCanyonId !== null;

  if (payload.honeypot) {
    errors.push("Spam detected");
  }
  if (!payload.publicConsent) {
    errors.push("Public consent is required");
  }
  if (!isDcOverlay && !payload.name) {
    errors.push("Name is required");
  }
  if (!isDcOverlay && !payload.country) {
    errors.push("Country is required");
  }
  if (!isDcOverlay && !payload.description) {
    errors.push("Description is required");
  }
  if (!payload.sources) {
    errors.push("At least one source is required");
  }
  if (payload.descenteCanyonId !== null && payload.descenteCanyonId <= 0) {
    errors.push("descenteCanyonId must be a positive integer");
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

function buildSubmissionTitle(payload) {
  if (payload.descenteCanyonId !== null) {
    return `[Overlay DC] ${payload.name || `dc:${payload.descenteCanyonId}`}`;
  }

  const locationParts = [payload.department, payload.region, payload.country].filter(Boolean);
  const location = locationParts.slice(0, 2).join(" - ");
  return location
    ? `[Nouveau canyon] ${payload.name} - ${location}`
    : `[Nouveau canyon] ${payload.name}`;
}

function buildPullRequestBody(payload, request, submissionPaths) {
  const submittedAt = new Date().toISOString();
  const remoteIpCountry = request.headers.get("CF-IPCountry") || "unknown";

  return [
    "## Summary",
    `- Type: ${describeSubmissionType(payload)}`,
    `- Cible: ${payload.descenteCanyonId !== null ? `dc:${payload.descenteCanyonId}` : `app:${submissionPaths.slug}`}`,
    submissionPaths.trackPath ? `- GPX: ${formatInlineCode(submissionPaths.trackPath)}` : "- GPX: aucun",
    "",
    "## Details",
    "",
    bulletLine("Nom", payload.name || `dc:${payload.descenteCanyonId}`, true),
    bulletLine("Nom complet", payload.fullName),
    bulletLine("Pseudo", payload.submitterPseudo),
    bulletLine("Pays", payload.country),
    bulletLine("Region", payload.region),
    bulletLine("Departement", payload.department),
    bulletLine("Commune", payload.municipality),
    bulletLine("Communes", payload.communes),
    bulletLine("Massif", payload.massif),
    bulletLine("Bassin", payload.basin),
    bulletLine("Cours d'eau", payload.watercourse),
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

function buildTrackingIssueTitle(payload) {
  return payload.descenteCanyonId !== null
    ? `[Suivi overlay] dc:${payload.descenteCanyonId}`
    : `[Suivi nouveau canyon] ${payload.name}`;
}

function buildTrackingIssueBody(payload, pullRequest) {
  return [
    "## Suivi public",
    "",
    `- Draft PR: ${pullRequest.html_url}`,
    `- Type: ${describeSubmissionType(payload)}`,
    payload.descenteCanyonId !== null
      ? `- Reference Descente-Canyon: dc:${payload.descenteCanyonId}`
      : `- Canyon propose: ${payload.name}`,
    bulletLine("Pseudo", payload.submitterPseudo),
    bulletLine("Pays", payload.country),
    bulletLine("Region", payload.region),
    bulletLine("Departement", payload.department),
    "",
    sectionBlock("## Resume", payload.description || payload.remarks || "Soumission publique en attente de revue."),
    "",
    sectionBlock("## Sources", payload.sources),
    "",
    `Cette issue suit la revue de ${pullRequest.html_url}.`,
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

function buildLabels(payload) {
  return payload.descenteCanyonId !== null ? ["overlay-dc"] : ["new-canyon"];
}

function describeSubmissionType(payload) {
  return payload.descenteCanyonId !== null ? "overlay Descente-Canyon" : "nouveau canyon app";
}

async function createSubmissionArtifacts({ payload, request, env }) {
  const repository = await getRepository(env);
  const submissionPaths = buildSubmissionPaths(payload);
  const branch = await createSubmissionBranch(env, repository.default_branch, submissionPaths.branchName);
  const files = buildSubmissionFiles(payload, submissionPaths);

  for (const file of files) {
    await putRepositoryFile({
      env,
      branch,
      path: file.path,
      content: file.content,
      message: file.message,
    });
  }

  const pullRequest = await createDraftPullRequest({
    env,
    title: buildSubmissionTitle(payload),
    body: buildPullRequestBody(payload, request, submissionPaths),
    head: branch,
    base: repository.default_branch,
  });

  await addLabelsToIssueLike(env, pullRequest.number, buildLabels(payload));

  const issue = await createTrackingIssue({
    env,
    title: buildTrackingIssueTitle(payload),
    body: buildTrackingIssueBody(payload, pullRequest),
    labels: buildLabels(payload),
  });

  return { pullRequest, issue };
}

function buildSubmissionPaths(payload) {
  const slug = payload.descenteCanyonId !== null
    ? `dc-${payload.descenteCanyonId}`
    : slugify(payload.name || "canyon");
  const date = new Date().toISOString().slice(0, 10);
  const uniqueSuffix = Math.random().toString(36).slice(2, 8);
  const branchName = `submission/${date}-${slug}-${uniqueSuffix}`;

  if (payload.descenteCanyonId !== null) {
    return {
      slug,
      branchName,
      overlayPath: `data-overlay/dc/${payload.descenteCanyonId}/overlay.json`,
      trackPath: payload.gpxTrace ? `data-overlay/dc/${payload.descenteCanyonId}/tracks/${buildTrackFileName(payload.gpxTrace.fileName)}` : null,
    };
  }

  return {
    slug,
    branchName,
    canyonPath: `data-overlay/app-canyons/${slug}/canyon.json`,
    trackPath: payload.gpxTrace ? `data-overlay/app-canyons/${slug}/tracks/${buildTrackFileName(payload.gpxTrace.fileName)}` : null,
  };
}

function buildSubmissionFiles(payload, submissionPaths) {
  const files = [];

  if (payload.descenteCanyonId !== null) {
    const overlay = {
      sourceKey: `dc:${payload.descenteCanyonId}`,
      tracks: submissionPaths.trackPath
        ? [
            {
              id: "main",
              name: "Trace principale",
              role: "MAIN",
              file: submissionPaths.trackPath.split(`/dc/${payload.descenteCanyonId}/`)[1],
              isPrimary: true,
            },
          ]
        : [],
    };

    files.push({
      path: submissionPaths.overlayPath,
      content: toPrettyJson(overlay),
      message: `submission: add overlay for dc:${payload.descenteCanyonId}`,
    });
  } else {
    const canyon = compactObject({
      sourceKey: `app:${submissionPaths.slug}`,
      name: payload.name,
      fullName: payload.fullName || payload.name,
      country: payload.country,
      region: payload.region,
      department: payload.department,
      municipality: payload.municipality,
      communes: parseTextList(payload.communes),
      massif: payload.massif,
      basin: payload.basin,
      watercourse: payload.watercourse,
      rating: hasRating(payload)
        ? {
            verticality: payload.ratingVerticality,
            aquatic: payload.ratingAquatic,
            engagement: payload.ratingEngagement,
          }
        : undefined,
      altitudeStart: payload.altitudeStart,
      elevation: payload.elevation,
      length: payload.length,
      maxWaterfall: payload.maxWaterfall,
      minRope: payload.minRope,
      approachTime: payload.approachTime,
      descentTime: payload.descentTime,
      returnTime: payload.returnTime,
      hasShuttle: payload.hasShuttle,
      interest: payload.interest,
      description: payload.description,
      accessDownstream: payload.accessDownstream,
      accessUpstream: payload.accessUpstream,
      approach: payload.approach,
      descent: payload.descent,
      returnRoute: payload.returnRoute,
      period: payload.period,
      geology: payload.geology,
      history: payload.history,
      remarks: payload.remarks,
      geoPoints: payload.geoPoints,
      tracks: submissionPaths.trackPath
        ? [
            {
              id: "main",
              name: "Trace principale",
              role: "MAIN",
              file: submissionPaths.trackPath.split(`/app-canyons/${submissionPaths.slug}/`)[1],
              isPrimary: true,
            },
          ]
        : undefined,
    });

    files.push({
      path: submissionPaths.canyonPath,
      content: toPrettyJson(canyon),
      message: `submission: add canyon ${payload.name}`,
    });
  }

  if (submissionPaths.trackPath && payload.gpxTrace?.rawContent) {
    files.push({
      path: submissionPaths.trackPath,
      content: payload.gpxTrace.rawContent,
      message: `submission: add GPX for ${payload.name || `dc:${payload.descenteCanyonId}`}`,
    });
  }

  return files;
}

async function getRepository(env) {
  return await githubJson(env, `/repos/${env.GITHUB_OWNER}/${env.GITHUB_REPO}`);
}

async function createSubmissionBranch(env, baseBranch, branchName) {
  const baseRef = await githubJson(env, `/repos/${env.GITHUB_OWNER}/${env.GITHUB_REPO}/git/ref/heads/${encodeURIComponent(baseBranch)}`);
  await githubJson(env, `/repos/${env.GITHUB_OWNER}/${env.GITHUB_REPO}/git/refs`, {
    method: "POST",
    body: {
      ref: `refs/heads/${branchName}`,
      sha: baseRef.object.sha,
    },
  });
  return branchName;
}

async function putRepositoryFile({ env, branch, path, content, message }) {
  const encodedPath = path.split("/").map(encodeURIComponent).join("/");
  return await githubJson(env, `/repos/${env.GITHUB_OWNER}/${env.GITHUB_REPO}/contents/${encodedPath}`, {
    method: "PUT",
    body: {
      message,
      branch,
      content: toBase64Utf8(content),
    },
  });
}

async function createDraftPullRequest({ env, title, body, head, base }) {
  return await githubJson(env, `/repos/${env.GITHUB_OWNER}/${env.GITHUB_REPO}/pulls`, {
    method: "POST",
    body: {
      title,
      body,
      head,
      base,
      draft: true,
    },
  });
}

async function createTrackingIssue({ env, title, body, labels }) {
  try {
    return await githubJson(env, `/repos/${env.GITHUB_OWNER}/${env.GITHUB_REPO}/issues`, {
      method: "POST",
      body: { title, body, labels },
    });
  } catch (error) {
    if (error.githubStatus !== 422) {
      throw error;
    }

    return await githubJson(env, `/repos/${env.GITHUB_OWNER}/${env.GITHUB_REPO}/issues`, {
      method: "POST",
      body: { title, body },
    });
  }
}

async function addLabelsToIssueLike(env, number, labels) {
  if (!labels || labels.length === 0) {
    return null;
  }

  try {
    return await githubJson(env, `/repos/${env.GITHUB_OWNER}/${env.GITHUB_REPO}/issues/${number}/labels`, {
      method: "POST",
      body: { labels },
    });
  } catch (error) {
    if (error.githubStatus === 422) {
      return null;
    }
    throw error;
  }
}

async function githubJson(env, path, init = {}) {
  const response = await fetch(`https://api.github.com${path}`, {
    method: init.method || "GET",
    headers: {
      Accept: "application/vnd.github+json",
      Authorization: `Bearer ${env.GITHUB_TOKEN}`,
      "Content-Type": "application/json",
      "User-Agent": "descente-canyon-canyon-submission-worker",
      "X-GitHub-Api-Version": "2022-11-28",
    },
    body: init.body ? JSON.stringify(init.body) : undefined,
  });

  if (!response.ok) {
    const body = await response.text();
    const error = new Error(`GitHub API ${response.status}: ${body}`);
    error.githubStatus = response.status;
    throw error;
  }

  return await response.json();
}

function slugify(value) {
  return String(value || "canyon")
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 80) || "canyon";
}

function buildTrackFileName(fileName) {
  const normalized = slugify(String(fileName || "main").replace(/\.gpx$/i, ""));
  return `${normalized || "main"}.gpx`;
}

function parseTextList(value) {
  if (!value) {
    return [];
  }

  return String(value)
    .split(/[,;\n]/)
    .map((item) => item.trim())
    .filter(Boolean);
}

function hasRating(payload) {
  return payload.ratingVerticality !== null && payload.ratingAquatic !== null && payload.ratingEngagement !== null;
}

function compactObject(value) {
  if (Array.isArray(value)) {
    return value.map(compactObject);
  }
  if (!value || typeof value !== "object") {
    return value;
  }

  return Object.fromEntries(
    Object.entries(value)
      .filter(([, entry]) => entry !== undefined && entry !== null && !(typeof entry === "string" && entry.trim() === ""))
      .map(([key, entry]) => [key, compactObject(entry)]),
  );
}

function toPrettyJson(value) {
  return `${JSON.stringify(value, null, 2)}\n`;
}

function toBase64Utf8(value) {
  const bytes = new TextEncoder().encode(String(value));
  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary);
}

function formatInlineCode(value) {
  return `\`${value}\``;
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
