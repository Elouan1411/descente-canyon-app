# Watershed Descriptor Audit

- Generated at: `2026-04-10T10:36:51.468816+00:00`
- Input: `watershed-results/runs/full/2026-04-10-world-v1/import_ready_watershed_descriptors.json`
- Rows: `3766`
- Columns: `167`
- Numeric columns: `153`
- Categorical/status columns: `14`

## Family Coverage

- `altitude`: `9` columns, average coverage `1.000`
- `climate`: `10` columns, average coverage `1.000`
- `flow_network`: `30` columns, average coverage `0.983`
- `geology_karst`: `13` columns, average coverage `0.916`
- `glacier`: `3` columns, average coverage `0.663`
- `land_cover`: `20` columns, average coverage `0.948`
- `metadata`: `5` columns, average coverage `1.000`
- `misc`: `4` columns, average coverage `1.000`
- `quality`: `6` columns, average coverage `1.000`
- `regulation`: `30` columns, average coverage `0.669`
- `slope_aspect`: `12` columns, average coverage `1.000`
- `soil`: `17` columns, average coverage `0.982`
- `status`: `8` columns, average coverage `0.897`

## Status Columns

- `descriptorStatus`: `ok`=3766
- `gdwStatus`: `ok`=3765, `error:GEOSException`=1
- `geologyDescriptorStatus`: `ok`=3766
- `glacierDescriptorStatus`: `ok`=3706, `error:GEOSException`=60
- `hydroLakesStatus`: `ok`=3765, `error:GEOSException`=1
- `imperviousDescriptorStatus`: `ok`=3766
- `landCoverDescriptorStatus`: `ok`=648
- `soilDescriptorStatus`: `ok`=3736, `error:RasterioIOError`=30

## Lowest Coverage Columns

- `gdwMaxDamHeightM` (`regulation`): coverage `0.002`
- `largestUpstreamReservoirAreaKm2` (`regulation`): coverage `0.003`
- `largestUpstreamReservoirStorageMcm` (`regulation`): coverage `0.003`
- `gdwNewestUpstreamDamYear` (`regulation`): coverage `0.007`
- `distanceToNearestRegulationUpstreamKm` (`regulation`): coverage `0.008`
- `gdwLargestReservoirAreaKm2` (`regulation`): coverage `0.008`
- `gdwLargestReservoirStorageMcm` (`regulation`): coverage `0.008`
- `gdwMaxUpstreamDorPct` (`regulation`): coverage `0.008`
- `regulatedAreaFraction` (`regulation`): coverage `0.008`
- `regulationSeverityIndex` (`regulation`): coverage `0.008`
- `largestGlacierAreaKm2` (`glacier`): coverage `0.021`
- `landCoverDescriptorStatus` (`status`): coverage `0.172`
- `imperviousConnectivityProxy` (`land_cover`): coverage `0.237`
- `largestForestPatchFraction` (`land_cover`): coverage `0.827`
- `landCoverFragmentationIndex` (`land_cover`): coverage `0.890`
- `carbonateFraction` (`geology_karst`): coverage `0.909`
- `crystallineFraction` (`geology_karst`): coverage `0.909`
- `dominantLithologyCode` (`geology_karst`): coverage `0.909`
- `evaporiteFraction` (`geology_karst`): coverage `0.909`
- `karstConnectivityIndex` (`geology_karst`): coverage `0.909`

## Top Issue Counts

- `negative_non_expected`: `42`
- `fraction_out_of_range`: `14`

## Strong Correlations

- `basinMinElevationM` <-> `outletElevationM`: `1.0` on `3766` rows
- `damCountUpstream` <-> `majorReservoirDamCountUpstream`: `1.0` on `3765` rows
- `damCountUpstream` <-> `regulatedLakeCountUpstream`: `1.0` on `3765` rows
- `damCountUpstream` <-> `reservoirCountUpstream`: `1.0` on `3765` rows
- `imperviousBuiltSurfaceFraction` <-> `meanBuiltSurfaceM2PerCell`: `1.0` on `3766` rows
- `imperviousProxyFraction` <-> `urbanFraction`: `1.0` on `3766` rows
- `mainFlowLengthKm` <-> `maxFlowPathLengthKm`: `1.0` on `3557` rows
- `majorReservoirDamCountUpstream` <-> `regulatedLakeCountUpstream`: `1.0` on `3765` rows
- `majorReservoirDamCountUpstream` <-> `reservoirCountUpstream`: `1.0` on `3765` rows
- `regulatedLakeCountUpstream` <-> `reservoirCountUpstream`: `1.0` on `3765` rows
- `streamCellCount` <-> `streamSegmentCount`: `1.0` on `3766` rows
- `watershedCellCount` <-> `watershedValidCellCount`: `1.0` on `3766` rows
- `basinMeanElevationM` <-> `basinMedianElevationM`: `0.999153` on `3766` rows
- `meanSandTopsoilPct` <-> `medianSandTopsoilPct`: `0.998778` on `3700` rows
- `meanClayTopsoilPct` <-> `medianClayTopsoilPct`: `0.99821` on `3700` rows
- `carbonateFraction` <-> `karstIndicator`: `0.993116` on `3424` rows
- `karstConnectivityIndex` <-> `karstIndicator`: `0.992379` on `3424` rows
- `handMeanM` <-> `handP90M`: `0.988794` on `3766` rows
- `karstIndicator` <-> `resurgenceIndicator`: `0.988078` on `3424` rows
- `handMeanM` <-> `handMedianM`: `0.987494` on `3766` rows
- `carbonateFraction` <-> `karstConnectivityIndex`: `0.985607` on `3424` rows
- `meanSlopeDeg` <-> `medianSlopeDeg`: `0.98327` on `3766` rows
- `carbonateFraction` <-> `resurgenceIndicator`: `0.982977` on `3424` rows
- `basinElevationStdM` <-> `basinReliefM`: `0.982157` on `3766` rows
- `karstConnectivityIndex` <-> `resurgenceIndicator`: `0.980525` on `3424` rows
- `lakeFraction` <-> `reservoirAreaFraction`: `0.978465` on `3765` rows
- `channelConfinementRatio` <-> `valleyConfinementIndex`: `-0.978329` on `3766` rows
- `meanAnnualTemperatureC` <-> `meanWinterTemperatureC`: `0.975203` on `3766` rows
- `gdwRegulatedCatchment` <-> `gdwReservoirCountUpstream`: `0.973526` on `3765` rows
- `gdwReservoirStorageUpstreamMcm` <-> `reservoirStorageUpstreamMcm`: `0.971063` on `3765` rows
