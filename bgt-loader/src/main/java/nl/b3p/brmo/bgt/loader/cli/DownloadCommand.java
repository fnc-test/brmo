/*
 * Copyright (C) 2021 B3Partners B.V.
 *
 * SPDX-License-Identifier: MIT
 */

package nl.b3p.brmo.bgt.loader.cli;

import nl.b3p.brmo.bgt.download.api.DeltaApi;
import nl.b3p.brmo.bgt.download.api.DownloadApiUtils;
import nl.b3p.brmo.bgt.download.client.ApiClient;
import nl.b3p.brmo.bgt.download.client.ApiException;
import nl.b3p.brmo.bgt.download.model.Delta;
import nl.b3p.brmo.bgt.download.model.GetDeltasResponse;
import nl.b3p.brmo.bgt.loader.BGTDatabase;
import nl.b3p.brmo.bgt.loader.ProgressReporter;
import nl.b3p.brmo.bgt.loader.ResumingBGTDownloadInputStream;
import nl.b3p.brmo.bgt.schema.BGTObjectTableWriter;
import org.apache.commons.io.input.CloseShieldInputStream;
import org.apache.commons.io.input.CountingInputStream;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.ExitCode;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.InputStream;
import java.net.URI;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static nl.b3p.brmo.bgt.loader.Utils.formatTimeSince;
import static nl.b3p.brmo.bgt.loader.Utils.getBundleString;
import static nl.b3p.brmo.bgt.loader.Utils.getLoaderVersion;
import static nl.b3p.brmo.bgt.loader.Utils.getMessageFormattedString;
import static nl.b3p.brmo.bgt.loader.Utils.getUserAgent;
import static nl.b3p.brmo.bgt.schema.BGTSchemaMapper.Metadata;

@Command(name = "download", mixinStandardHelpOptions = true)
public class DownloadCommand {
    private static final Log log = LogFactory.getLog(DownloadCommand.class);

    private static final String PREDEFINED_FULL_DELTA_URI = "https://api.pdok.nl/lv/bgt/download/v1_0/delta/predefined/bgt-citygml-nl-delta.zip";

    public static ApiClient getApiClient(URI baseUri) {
        ApiClient client = new ApiClient();
        if (baseUri != null) {
            client.updateBaseUri(baseUri.toString());
        }
        client.setRequestInterceptor(builder -> builder.headers("User-Agent", getUserAgent()));
        return client;
    }

    private BGTObjectTableWriter createWriter(BGTDatabase db, DatabaseOptions dbOptions, LoadOptions loadOptions, CLIOptions cliOptions) throws SQLException {
        BGTObjectTableWriter writer = db.createObjectTableWriter(loadOptions, dbOptions);
        ProgressReporter progressReporter;
        if (cliOptions.isConsoleProgressEnabled()) {
            progressReporter = new ConsoleProgressReporter();
        } else {
            progressReporter = new ProgressReporter();
        }
        writer.setProgressUpdater(progressReporter);
        return writer;
    }

    @Command(name="initial", sortOptions = false)
    public int initial(
            @Mixin DatabaseOptions dbOptions,
            @Mixin LoadOptions loadOptions,
            @Mixin ExtractSelectionOptions extractSelectionOptions,
            @Option(names="--no-geo-filter") boolean noGeoFilter,
            @Option(names="--download-service", hidden = true) URI downloadServiceURI,
            @Mixin CLIOptions cliOptions,
            @Option(names={"-h","--help"}, usageHelp = true) boolean showHelp
    ) throws Exception {

        if (extractSelectionOptions.getGeoFilterWkt() == null && !noGeoFilter) {
            System.err.println(getBundleString("download.no_geo_filter"));
            return ExitCode.USAGE;
        }

        log.info(getUserAgent());

        try(BGTDatabase db = new BGTDatabase(dbOptions)) {
            if (loadOptions.createSchema) {
                db.createMetadataTable(loadOptions);
            } else {
                log.info(getBundleString("download.connect_db"));
            }

            db.setMetadataValue(Metadata.LOADER_VERSION, getLoaderVersion());
            db.setFeatureTypesEnumMetadata(extractSelectionOptions.getFeatureTypesList());
            db.setMetadataValue(Metadata.INCLUDE_HISTORY, loadOptions.includeHistory + "");
            db.setMetadataValue(Metadata.LINEARIZE_CURVES, loadOptions.linearizeCurves + "");
            db.setMetadataValue(Metadata.TABLE_PREFIX, loadOptions.tablePrefix);

            // Close connection while waiting for extract
            db.close();

            BGTObjectTableWriter writer = createWriter(db, dbOptions, loadOptions, cliOptions);
            Instant start = Instant.now();

            if (noGeoFilter) {
                loadFromPredefinedDelta(db, writer, extractSelectionOptions, loadOptions);
            } else {
                printApiException(() -> {
                    log.info(getBundleString("download.create"));

                    ApiClient client = getApiClient(downloadServiceURI);
                    URI downloadURI = DownloadApiUtils.getCustomDownloadURL(client, null, extractSelectionOptions, new CustomDownloadProgressReporter(cliOptions.isConsoleProgressEnabled()));

                    loadFromURI(db, writer, extractSelectionOptions, downloadURI, start);
                    return ExitCode.OK;
                });
            }
            db.setMetadataValue(Metadata.DELTA_TIME_TO, null);
            db.getConnection().commit();
            return ExitCode.OK;
        }
    }

    @Command(name="update", sortOptions = false)
    public int update(
            @Mixin DatabaseOptions dbOptions,
            @Mixin CLIOptions cliOptions,
            @Option(names="--download-service", hidden = true) URI downloadServiceURI,
            @Option(names={"-h","--help"}, usageHelp = true) boolean showHelp
    ) throws Exception {
        log.info(getUserAgent());

        ApiClient client = getApiClient(downloadServiceURI);

        log.info(getBundleString("download.connect_db"));
        try(BGTDatabase db = new BGTDatabase(dbOptions)) {
            String deltaId = db.getMetadata(Metadata.DELTA_ID);
            OffsetDateTime deltaIdTimeTo = null;
            String s = db.getMetadata(Metadata.DELTA_TIME_TO);
            if (s != null && s.length() > 0) {
                deltaIdTimeTo = OffsetDateTime.parse(s);
            }
            if (deltaId == null) {
                System.err.println(getBundleString("download.no_delta_id"));
                return ExitCode.SOFTWARE;
            }
            ExtractSelectionOptions extractSelectionOptions = new ExtractSelectionOptions();
            extractSelectionOptions.setGeoFilterWkt(db.getMetadata(Metadata.GEOM_FILTER));
            if (extractSelectionOptions.getGeoFilterWkt() != null && extractSelectionOptions.getGeoFilterWkt().length() == 0) {
                extractSelectionOptions.setGeoFilterWkt(null);
            }
            extractSelectionOptions.setFeatureTypes(Arrays.asList(db.getMetadata(Metadata.FEATURE_TYPES).split(",")));
            LoadOptions loadOptions = new LoadOptions();
            loadOptions.includeHistory = Boolean.parseBoolean(db.getMetadata(Metadata.INCLUDE_HISTORY));
            loadOptions.linearizeCurves = Boolean.parseBoolean(db.getMetadata(Metadata.LINEARIZE_CURVES));
            loadOptions.tablePrefix = db.getMetadata(Metadata.TABLE_PREFIX);

            log.info(getMessageFormattedString("download.current_delta_id", deltaId) + ", " +
                    (deltaIdTimeTo != null
                            ? getMessageFormattedString("download.current_delta_time", DateTimeFormatter.ISO_INSTANT.format(deltaIdTimeTo))
                            : getBundleString("download.current_delta_time_unknown")));
            // Close connection while waiting for extract
            db.close();

            return printApiException(() -> {
                Instant start = Instant.now();
                log.info(getBundleString("download.loading_deltas"));
                // Note that the afterDeltaId parameter is useless, because the response does not distinguish between
                // "'afterDeltaId' is the latest" and "'afterDeltaId' not found or older than 31 days"
                GetDeltasResponse response = new DeltaApi(client).getDeltas(null, 1, 100);

                // Verify no links to other page, as we expect at most 31 delta's
                if (response.getLinks() != null && !response.getLinks().isEmpty()) {
                    throw new IllegalStateException("Did not expect links in GetDeltas response");
                }

                int i;
                for (i = 0; i < response.getDeltas().size(); i++) {
                    Delta d = response.getDeltas().get(i);
                    if (deltaId.equals(d.getId())) {
                        break;
                    }
                }
                if (i == response.getDeltas().size()) {
                    // TODO automatically do initial load depending on option
                    System.err.println(getBundleString("download.current_delta_not_found"));
                    return ExitCode.SOFTWARE;
                }

                List<Delta> deltas = response.getDeltas().subList(i + 1, response.getDeltas().size());
                if (deltas.isEmpty()) {
                    log.info(getBundleString("download.uptodate"));
                    return ExitCode.OK;
                }

                Delta latestDelta = deltas.get(deltas.size() - 1);
                log.info(getMessageFormattedString("download.updates_available", deltas.size(), latestDelta.getId(), latestDelta.getTimeWindow().getTo()));

                BGTObjectTableWriter writer = createWriter(db, dbOptions, loadOptions, cliOptions);

                int deltaCount = 1;
                for (Delta delta : deltas) {
                    log.info(getMessageFormattedString("download.creating_download", deltaCount++, deltas.size(), delta.getId()));
                    URI downloadURI = DownloadApiUtils.getCustomDownloadURL(client, delta, extractSelectionOptions, new CustomDownloadProgressReporter(cliOptions.isConsoleProgressEnabled()));
                    // TODO: BGTObjectTableWriter does setAutocommit(false) and commit() after each stream for a featuretype
                    // is written, maybe use one transaction for all feature types?
                    loadFromURI(db, writer, extractSelectionOptions, downloadURI, start);
                    db.setMetadataValue(Metadata.DELTA_TIME_TO, delta.getTimeWindow().getTo().toString());
                    db.getConnection().commit();
                }

                db.setMetadataValue(Metadata.LOADER_VERSION, getLoaderVersion());
                db.getConnection().commit();
                return ExitCode.OK;
            });
        }
    }

    private static int printApiException(Callable<Integer> callable) throws Exception {
        try {
            return callable.call();
        } catch(ApiException apiException) {
            System.err.printf("API status code: %d, body: %s\n", apiException.getCode(), apiException.getResponseBody());
            throw apiException;
        }
    }

    private static void loadFromPredefinedDelta(BGTDatabase db, BGTObjectTableWriter writer, FeatureTypeSelectionOptions featureTypeSelectionOptions, LoadOptions loadOptions) throws Exception {
        Instant loadStart = Instant.now();
        if (loadOptions.isHttpZipRandomAccess()) {
            new BGTLoaderMain().loadZipFromURIUsingRandomAccess(new URI(PREDEFINED_FULL_DELTA_URI), writer, featureTypeSelectionOptions, loadOptions.isDebugHttpSeeks());
        } else {
            new BGTLoaderMain().loadZipFromURI(new URI(PREDEFINED_FULL_DELTA_URI), writer, featureTypeSelectionOptions);
        }
        db.setMetadataForMutaties(writer.getProgress().getMutatieInhoud());
        db.setMetadataValue(Metadata.GEOM_FILTER, "");
        log.info(getMessageFormattedString("download.complete",
                getBundleString("download.mutatietype." + writer.getProgress().getMutatieInhoud().getMutatieType()),
                writer.getProgress().getMutatieInhoud().getLeveringsId(),
                formatTimeSince(loadStart))
        );
    }

    private static void loadFromURI(BGTDatabase db, BGTObjectTableWriter writer, ExtractSelectionOptions extractSelectionOptions, URI downloadURI, Instant start) throws Exception {
        log.info(getMessageFormattedString("download.downloading_from", downloadURI));
        ProgressReporter progressReporter = (ProgressReporter) writer.getProgressUpdater();

        try (InputStream input = new ResumingBGTDownloadInputStream(downloadURI, writer)) {
            Instant loadStart = Instant.now();
            CountingInputStream countingInputStream = new CountingInputStream(input);
            progressReporter.setTotalBytesReadFunction(countingInputStream::getByteCount);
            try(ZipInputStream zis = new ZipInputStream(countingInputStream)) {
                ZipEntry entry = zis.getNextEntry();
                while (entry != null) {
                    progressReporter.startNewFile(entry.getName(), null);
                    writer.write(CloseShieldInputStream.wrap(zis));
                    entry = zis.getNextEntry();
                }
            }

            db.setMetadataForMutaties(writer.getProgress().getMutatieInhoud());
            // Do not set geom filter from MutatieInhoud, a custom download without geo filter will have gebied
            // "POLYGON ((-100000 200000, 412000 200000, 412000 712000, -100000 712000, -100000 200000))"
            db.setMetadataValue(Metadata.GEOM_FILTER, extractSelectionOptions.getGeoFilterWkt());

            log.info(getMessageFormattedString("download.complete",
                    getBundleString("download.mutatietype." + writer.getProgress().getMutatieInhoud().getMutatieType()),
                    writer.getProgress().getMutatieInhoud().getLeveringsId(),
                    formatTimeSince(loadStart)) +
                    (start == null ? "" : " " + getMessageFormattedString("download.complete_total", formatTimeSince(start)))
            );
        }
    }
}