import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const repoRoot = path.resolve(__dirname, "..", "..");

const overlayRoot = path.join(repoRoot, "data-overlay");
const roomImportRoot = path.join(repoRoot, "offline-data", "full", "room-import");
const runtimeIdMapPath = path.join(overlayRoot, "runtime-id-map.json");

const APP_SOURCE_TYPE = "APP";
const DC_SOURCE_TYPE = "DESCENTE_CANYON";

main();

function main() {
  const generatedAt = new Date().toISOString();
  const runtimeIdMap = readJson(runtimeIdMapPath);

  const baseManifest = readJson(path.join(roomImportRoot, "manifest.json"));
  const baseCanyons = readJson(path.join(roomImportRoot, "canyons.json"));
  const baseGeoPoints = readJson(path.join(roomImportRoot, "geo_points.json"));
  const baseBibliographyEntries = readJson(path.join(roomImportRoot, "bibliography_entries.json"));
  const baseCanyonBibliography = readJson(path.join(roomImportRoot, "canyon_bibliography.json"));
  const baseRegulationTexts = readJson(path.join(roomImportRoot, "regulation_texts.json"));
  const baseCanyonRegulations = readJson(path.join(roomImportRoot, "canyon_regulations.json"));
  const baseWatersheds = readOptionalJson(path.join(roomImportRoot, "watersheds.json")) ?? [];
  const baseTracks = readOptionalJson(path.join(roomImportRoot, "tracks.json")) ?? [];

  const baseDcCanyons = baseCanyons
    .filter((row) => (row.sourceType ?? DC_SOURCE_TYPE) !== APP_SOURCE_TYPE)
    .map((row) => ({
      ...row,
      sourceType: row.sourceType ?? DC_SOURCE_TYPE,
      sourceKey: row.sourceKey ?? `dc:${row.id}`,
    }));
  const knownAppIds = new Set(
    baseCanyons
      .filter((row) => (row.sourceType ?? DC_SOURCE_TYPE) === APP_SOURCE_TYPE)
      .map((row) => row.id),
  );

  const canyonById = new Map(baseDcCanyons.map((row) => [row.id, row]));
  const mergedCanyons = [...baseDcCanyons];
  const mergedGeoPoints = baseGeoPoints.filter((row) => !knownAppIds.has(row.canyonId));
  const mergedWatershedsByCanyonId = new Map(
    baseWatersheds
      .filter((row) => (row.sourceType ?? DC_SOURCE_TYPE) !== APP_SOURCE_TYPE)
      .map((row) => [row.canyonId, row]),
  );
  const mergedTracks = baseTracks.filter((row) => (row.sourceType ?? DC_SOURCE_TYPE) !== APP_SOURCE_TYPE);

  const appCanyonDirs = listDatasetDirectories(path.join(overlayRoot, "app-canyons"), "canyon.json");
  appCanyonDirs.forEach((dirName) => {
    const canyonDir = path.join(overlayRoot, "app-canyons", dirName);
    const canyonPayload = readJson(path.join(canyonDir, "canyon.json"));
    validateAppCanyon(canyonPayload, dirName);

    const sourceKey = canyonPayload.sourceKey;
    const runtimeId = resolveAppRuntimeId(runtimeIdMap, sourceKey);
    const canyonRow = appCanyonToImportRow(canyonPayload, runtimeId, canyonDir, generatedAt);

    if (canyonById.has(runtimeId)) {
      throw new Error(`Runtime id collision for ${sourceKey}: ${runtimeId}`);
    }

    canyonById.set(runtimeId, canyonRow);
    mergedCanyons.push(canyonRow);

    (canyonPayload.geoPoints ?? []).forEach((point) => {
      mergedGeoPoints.push({
        canyonId: runtimeId,
        type: point.type,
        latitude: point.latitude,
        longitude: point.longitude,
        label: point.title ?? null,
        remark: point.remark ?? null,
      });
    });

    const watershedRow = buildWatershedRow(canyonPayload.watershed, runtimeId, APP_SOURCE_TYPE, sourceKey, canyonDir);
    if (watershedRow) {
      mergedWatershedsByCanyonId.set(runtimeId, watershedRow);
    }

    buildTrackRows(canyonPayload.tracks ?? [], runtimeId, APP_SOURCE_TYPE, sourceKey, canyonDir).forEach((trackRow) => {
      mergedTracks.push(trackRow);
    });
  });

  const dcOverlayDirs = listDatasetDirectories(path.join(overlayRoot, "dc"), "overlay.json");
  dcOverlayDirs.forEach((dirName) => {
    const overlayDir = path.join(overlayRoot, "dc", dirName);
    const overlayPayload = readJson(path.join(overlayDir, "overlay.json"));
    validateDcOverlay(overlayPayload, dirName);

    const canyonId = Number.parseInt(dirName, 10);
    const baseCanyon = canyonById.get(canyonId);
    if (!baseCanyon || baseCanyon.sourceType === APP_SOURCE_TYPE) {
      throw new Error(`Cannot apply overlay for unknown Descente-Canyon canyon ${overlayPayload.sourceKey}`);
    }

    const watershedRow = buildWatershedRow(
      overlayPayload.watershed,
      canyonId,
      APP_SOURCE_TYPE,
      overlayPayload.sourceKey,
      overlayDir,
    );
    if (watershedRow) {
      mergedWatershedsByCanyonId.set(canyonId, watershedRow);
    }

    buildTrackRows(overlayPayload.tracks ?? [], canyonId, APP_SOURCE_TYPE, overlayPayload.sourceKey, overlayDir).forEach((trackRow) => {
      mergedTracks.push(trackRow);
    });
  });

  const normalizedCanyons = mergedCanyons.sort((left, right) => left.id - right.id);
  const normalizedGeoPoints = mergedGeoPoints.sort((left, right) => left.canyonId - right.canyonId || left.latitude - right.latitude || left.longitude - right.longitude);
  const normalizedWatersheds = Array.from(mergedWatershedsByCanyonId.values()).sort((left, right) => left.canyonId - right.canyonId);
  const normalizedTracks = mergedTracks.sort((left, right) => {
    if (left.canyonId !== right.canyonId) return left.canyonId - right.canyonId;
    return String(left.trackKey).localeCompare(String(right.trackKey));
  });

  writeJson(path.join(roomImportRoot, "canyons.json"), normalizedCanyons);
  writeJson(path.join(roomImportRoot, "geo_points.json"), normalizedGeoPoints);
  writeJson(path.join(roomImportRoot, "watersheds.json"), normalizedWatersheds);
  writeJson(path.join(roomImportRoot, "tracks.json"), normalizedTracks);
  writeJson(runtimeIdMapPath, runtimeIdMap);

  const manifest = {
    schemaVersion: 2,
    generatedAt,
    tables: {
      ...baseManifest.tables,
      watersheds: "watersheds.json",
      tracks: "tracks.json",
    },
    counts: {
      canyons: normalizedCanyons.length,
      geo_points: normalizedGeoPoints.length,
      bibliography_entries: baseBibliographyEntries.length,
      canyon_bibliography: baseCanyonBibliography.length,
      regulation_texts: baseRegulationTexts.length,
      canyon_regulations: baseCanyonRegulations.length,
      watersheds: normalizedWatersheds.length,
      tracks: normalizedTracks.length,
    },
    versions: {
      ...(baseManifest.versions ?? {}),
      overlays: generatedAt,
      watersheds: generatedAt,
    },
  };

  writeJson(path.join(roomImportRoot, "manifest.json"), manifest);

  console.log(
    JSON.stringify(
      {
        generatedAt,
        appCanyons: appCanyonDirs.length,
        dcOverlays: dcOverlayDirs.length,
        counts: manifest.counts,
      },
      null,
      2,
    ),
  );
}

function appCanyonToImportRow(payload, runtimeId, canyonDir, generatedAt) {
  const slug = payload.sourceKey.replace(/^app:/, "");
  return {
    id: runtimeId,
    nom: payload.name,
    nomComplet: payload.fullName ?? payload.name,
    pays: payload.country,
    region: payload.region ?? null,
    departement: payload.department ?? null,
    commune: payload.municipality ?? "",
    communes: payload.communes ?? [],
    massif: payload.massif ?? null,
    bassin: payload.basin ?? null,
    coursEau: payload.watercourse ?? null,
    cotation: formatCotation(payload.rating),
    altitudeDepart: payload.altitudeStart ?? null,
    denivele: payload.elevation ?? null,
    longueur: payload.length ?? null,
    cascadeMax: payload.maxWaterfall ?? null,
    cordeMin: payload.minRope ?? null,
    tempsApproche: payload.approachTime ?? null,
    tempsDescente: payload.descentTime ?? null,
    tempsRetour: payload.returnTime ?? null,
    navette: payload.hasShuttle ? "oui" : null,
    interet: payload.interest ?? null,
    nbVotes: 0,
    url: `https://github.com/Plinz/descente-canyon-app/tree/main/data-overlay/app-canyons/${slug}`,
    accesAval: payload.accessDownstream ?? null,
    accesAmont: payload.accessUpstream ?? null,
    approche: payload.approach ?? null,
    descente: payload.descent ?? null,
    retour: payload.returnRoute ?? null,
    engagement: payload.rating?.engagement ? romanNumeral(payload.rating.engagement) : null,
    periode: payload.period ?? null,
    geologie: payload.geology ?? null,
    historique: payload.history ?? null,
    remarques: payload.remarks ?? null,
    isOffline: false,
    isFavorite: false,
    lastUpdated: Date.parse(generatedAt),
    hasSpecificRegulation: false,
    isForbidden: false,
    sourceType: APP_SOURCE_TYPE,
    sourceKey: payload.sourceKey,
  };
}

function buildWatershedRow(watershed, canyonId, sourceType, sourceKey, baseDir) {
  if (!watershed) {
    return null;
  }

  const polygonPath = watershed.polygonFile ? path.join(baseDir, watershed.polygonFile) : null;
  const polygonPayload = polygonPath ? readJson(polygonPath) : null;
  const bbox = polygonPayload ? computeGeoJsonBounds(polygonPayload) : null;

  return {
    canyonId,
    upstreamCatchmentAreaKm2: watershed.areaKm2 ?? null,
    bbox: bbox ? [bbox.minLongitude, bbox.minLatitude, bbox.maxLongitude, bbox.maxLatitude] : null,
    geometry: polygonPayload,
    sourceType,
    sourceKey,
  };
}

function buildTrackRows(tracks, canyonId, sourceType, sourceKey, baseDir) {
  return tracks.map((track) => {
    const filePath = path.join(baseDir, track.file);
    const parsed = parseGpx(fs.readFileSync(filePath, "utf8"));
    return {
      canyonId,
      trackKey: `${sourceKey}:${track.id}`,
      name: track.name,
      role: track.role ?? null,
      isPrimary: track.isPrimary === true,
      pointCount: parsed.pointCount,
      waypointCount: parsed.waypointCount,
      bbox: parsed.bbox,
      geometry: parsed.geometry,
      sourceType,
      sourceKey,
    };
  });
}

function parseGpx(content) {
  const segments = [];
  const trackSegRegex = /<trkseg\b[^>]*>([\s\S]*?)<\/trkseg>/gi;
  const routeRegex = /<rte\b[^>]*>([\s\S]*?)<\/rte>/gi;
  const waypoints = extractPoints(content, /<wpt\b[^>]*?lat="([^"]+)"[^>]*?lon="([^"]+)"[^>]*?(?:\/>|>[\s\S]*?<\/wpt>)/gi);

  let match;
  while ((match = trackSegRegex.exec(content)) !== null) {
    const points = extractPoints(match[1], /<trkpt\b[^>]*?lat="([^"]+)"[^>]*?lon="([^"]+)"[^>]*?(?:\/>|>[\s\S]*?<\/trkpt>)/gi);
    if (points.length > 0) {
      segments.push(points);
    }
  }

  while ((match = routeRegex.exec(content)) !== null) {
    const points = extractPoints(match[1], /<rtept\b[^>]*?lat="([^"]+)"[^>]*?lon="([^"]+)"[^>]*?(?:\/>|>[\s\S]*?<\/rtept>)/gi);
    if (points.length > 0) {
      segments.push(points);
    }
  }

  const allPoints = [...segments.flat(), ...waypoints];
  if (allPoints.length === 0) {
    throw new Error("GPX file does not contain any coordinate");
  }

  const bbox = allPoints.reduce(
    (accumulator, [latitude, longitude]) => ({
      minLatitude: Math.min(accumulator.minLatitude, latitude),
      minLongitude: Math.min(accumulator.minLongitude, longitude),
      maxLatitude: Math.max(accumulator.maxLatitude, latitude),
      maxLongitude: Math.max(accumulator.maxLongitude, longitude),
    }),
    {
      minLatitude: allPoints[0][0],
      minLongitude: allPoints[0][1],
      maxLatitude: allPoints[0][0],
      maxLongitude: allPoints[0][1],
    },
  );

  let geometry;
  if (segments.length === 1) {
    geometry = {
      type: "LineString",
      coordinates: segments[0].map(([latitude, longitude]) => [longitude, latitude]),
    };
  } else if (segments.length > 1) {
    geometry = {
      type: "MultiLineString",
      coordinates: segments.map((segment) => segment.map(([latitude, longitude]) => [longitude, latitude])),
    };
  } else if (waypoints.length === 1) {
    geometry = {
      type: "Point",
      coordinates: [waypoints[0][1], waypoints[0][0]],
    };
  } else {
    geometry = {
      type: "LineString",
      coordinates: waypoints.map(([latitude, longitude]) => [longitude, latitude]),
    };
  }

  return {
    pointCount: allPoints.length,
    waypointCount: waypoints.length,
    bbox,
    geometry,
  };
}

function extractPoints(content, regex) {
  const points = [];
  let match;
  while ((match = regex.exec(content)) !== null) {
    const latitude = Number.parseFloat(match[1]);
    const longitude = Number.parseFloat(match[2]);
    if (Number.isFinite(latitude) && Number.isFinite(longitude)) {
      points.push([latitude, longitude]);
    }
  }
  return points;
}

function computeGeoJsonBounds(geoJson) {
  const coordinates = [];
  collectCoordinates(geoJson, coordinates);
  if (coordinates.length === 0) {
    return null;
  }

  return coordinates.reduce(
    (accumulator, [longitude, latitude]) => ({
      minLongitude: Math.min(accumulator.minLongitude, longitude),
      minLatitude: Math.min(accumulator.minLatitude, latitude),
      maxLongitude: Math.max(accumulator.maxLongitude, longitude),
      maxLatitude: Math.max(accumulator.maxLatitude, latitude),
    }),
    {
      minLongitude: coordinates[0][0],
      minLatitude: coordinates[0][1],
      maxLongitude: coordinates[0][0],
      maxLatitude: coordinates[0][1],
    },
  );
}

function collectCoordinates(node, target) {
  if (!node) {
    return;
  }
  if (Array.isArray(node)) {
    if (node.length >= 2 && typeof node[0] === "number" && typeof node[1] === "number") {
      target.push([node[0], node[1]]);
      return;
    }
    node.forEach((item) => collectCoordinates(item, target));
    return;
  }
  if (typeof node === "object") {
    Object.values(node).forEach((value) => collectCoordinates(value, target));
  }
}

function resolveAppRuntimeId(runtimeIdMap, sourceKey) {
  if (runtimeIdMap.entries[sourceKey] != null) {
    return runtimeIdMap.entries[sourceKey];
  }

  const runtimeId = runtimeIdMap.nextAppRuntimeId;
  runtimeIdMap.entries[sourceKey] = runtimeId;
  runtimeIdMap.nextAppRuntimeId = runtimeId + 1;
  return runtimeId;
}

function validateAppCanyon(payload, dirName) {
  if (payload.sourceKey !== `app:${dirName}`) {
    throw new Error(`App canyon directory ${dirName} must match sourceKey ${payload.sourceKey}`);
  }
  if (!payload.name || !payload.country || !payload.description) {
    throw new Error(`App canyon ${payload.sourceKey} is missing required fields`);
  }
  validateRating(payload.rating, payload.sourceKey);
}

function validateDcOverlay(payload, dirName) {
  if (payload.sourceKey !== `dc:${dirName}`) {
    throw new Error(`DC overlay directory ${dirName} must match sourceKey ${payload.sourceKey}`);
  }
}

function validateRating(rating, sourceKey) {
  if (!rating) {
    throw new Error(`Missing rating for ${sourceKey}`);
  }
  [rating.verticality, rating.aquatic, rating.engagement].forEach((value) => {
    if (!Number.isInteger(value) || value < 1 || value > 9) {
      throw new Error(`Invalid rating component for ${sourceKey}`);
    }
  });
}

function formatCotation(rating) {
  return `v${rating.verticality} a${rating.aquatic} ${romanNumeral(rating.engagement)}`;
}

function romanNumeral(value) {
  return {
    1: "I",
    2: "II",
    3: "III",
    4: "IV",
    5: "V",
    6: "VI",
    7: "VII",
    8: "VIII",
    9: "IX",
  }[value] ?? String(value);
}

function listImmediateDirectories(directoryPath) {
  if (!fs.existsSync(directoryPath)) {
    return [];
  }
  return fs.readdirSync(directoryPath, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => entry.name)
    .sort();
}

function listDatasetDirectories(directoryPath, expectedFileName) {
  return listImmediateDirectories(directoryPath).filter((dirName) =>
    fs.existsSync(path.join(directoryPath, dirName, expectedFileName)),
  );
}

function readJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, "utf8"));
}

function readOptionalJson(filePath) {
  return fs.existsSync(filePath) ? readJson(filePath) : null;
}

function writeJson(filePath, payload) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
}
