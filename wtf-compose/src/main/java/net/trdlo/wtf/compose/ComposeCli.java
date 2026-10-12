package net.trdlo.wtf.compose;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import net.trdlo.wtf.tiles.*;

import java.nio.file.Path;
import java.util.Locale;

/**
 * The {@code wtf-compose} command line.
 * <p>
 * This is what makes M2 testable: at the end of it the original use case is achievable without a
 * single window. Exit codes are meaningful – {@code 0} success, {@code 1} a job error, {@code 2}
 * bad arguments, {@code 4} tiles missing – and every line is prefixed {@code [compose]}.
 */
public final class ComposeCli {

    private static final String PREFIX = "[compose]";

    private ComposeCli() {
    }

    public static void main(final String[] args) {
        ConsoleOutput.configureLogging();
        System.setOut(ConsoleOutput.wrap(System.out));
        System.setErr(ConsoleOutput.wrap(System.err));
        System.exit(run(args));
    }

    static int run(final String[] args) {
        if (args.length == 0 || "-h".equals(args[0]) || "--help".equals(args[0])) {
            usage();
            return args.length == 0 ? 2 : 0;
        }
        try {
            // Inside the handling: a mistyped option is a bad argument, exit 2, not a stack trace
            final Args a = Args.parse(java.util.Arrays.copyOfRange(args, 1, args.length));
            return switch (args[0]) {
                case "layout" -> layout(a);
                case "inventory" -> inventory(a);
                case "export" -> export(a);
                case "sample" -> sample(a);
                default -> {
                    System.err.println(PREFIX + " unknown command: " + args[0]);
                    usage();
                    yield 2;
                }
            };
        } catch (final Composer.MissingTilesException ex) {
            System.err.println(PREFIX + " " + ex.getMessage());
            System.err.println(PREFIX + " download them first, or choose what goes in the gaps:");
            System.err.println(PREFIX + "   --missing transparent  leave the paper showing (printable)");
            System.err.println(PREFIX + "   --missing placeholder  mark them visibly (a proof, not a print)");
            return 4;
        } catch (final NothingThereException ex) {
            // From an export, and from inventory and --dry-run alike, whatever --missing says
            System.err.println(PREFIX + " " + ex.getMessage());
            System.err.println(PREFIX + " check the provider, theme and area: a real print has at least"
                    + " one tile that is not sea or past the provider's coverage, whatever --missing says");
            return 1;
        } catch (final IllegalArgumentException ex) {
            System.err.println(PREFIX + " " + ex.getMessage());
            return 2;
        } catch (final Exception ex) {
            System.err.println(PREFIX + " " + ex);
            return 1;
        }
    }

    private static void usage() {
        System.out.println("""
                wtf-compose - shapes and tiles into print files

                  layout    project.wtf          the sheet table and the scale numbers
                  inventory project.wtf          tiles needed, cached and missing, per region
                  export    project.wtf          write the print files
                  sample    --out project.wtf --bbox W,S,E,N [--zoom Z] [--width MM]
                                                      write a minimal project to start from

                Options:
                  --cache DIR        tile cache root
                  --providers FILE   merged over the shipped ones (default ~/.wtf/providers.json)
                  --out DIR          output directory, overriding the project
                  --band-tiles N     rows of tiles held in memory by the raster exporters
                  --missing WHAT     what to do about tiles that are not cached:
                                     refuse (the default), transparent, placeholder
                  --dry-run          resolve everything, write nothing
                  --json             machine-readable output
                """);
    }

    // -------------------------------------------------------------------------------------------

    private static int layout(final @NotNull Args a) throws Exception {
        final ComposeJob job = job(a);
        final SheetLayout layout = new Composer().layout(job);
        final PrintRect print = layout.print();
        final int zoom = job.regions().zoom();
        // The zooms around the print's own, at its scale: @2x tiles double every row's DPI
        final int scale = job.regions().scale();

        if (a.json) {
            System.out.printf(Locale.ROOT,
                    "{\"sheets\":%d,\"columns\":%d,\"rows\":%d,\"widthMm\":%.1f,\"heightMm\":%.1f,"
                            + "\"dpi\":%.1f,\"scale\":%.0f,\"labelMm\":%.2f}%n",
                    layout.count(), layout.columns(), layout.rows(),
                    print.widthMm(), print.heightMm(), print.dpi(zoom, scale), print.mapScale(),
                    print.labelHeightMm(zoom));
            return 0;
        }
        System.out.println(PREFIX + " " + job.medium().describe() + ", " + job.regions().describe());
        System.out.printf(Locale.ROOT, "%s print %.0f x %.0f mm, scale 1:%,.0f%n",
                PREFIX, print.widthMm(), print.heightMm(), print.mapScale());
        System.out.println();
        System.out.printf(Locale.ROOT, "  %-5s %-10s %-12s %-12s %s%n",
                "zoom", "DPI", "label mm", "px across", "readable?");
        final Provider p = job.provider();
        final Provider.Theme t = p.requireTheme(job.themeId());
        for (int z = Math.max(p.minZoomOf(t), zoom - 4); z <= Math.min(p.maxZoomOf(t), zoom + 3); z++) {
            final double mm = print.labelHeightMm(z);
            System.out.printf(Locale.ROOT, "  %-5s %-10.0f %-12.2f %-12.0f %s%s%n",
                    (z == zoom ? "*" : "") + z, print.dpi(z, scale), mm, print.pixelWidth(z, scale),
                    mm >= 1.5 ? "yes" : "no", z == zoom ? "   <- chosen" : "");
        }
        System.out.println();
        // The layout alone: planning the tiles would list every one of them to print a table
        System.out.println(layout.describe());
        return 0;
    }

    private static int inventory(final @NotNull Args a) throws Exception {
        final ComposeJob job = job(a);
        final TileCache cache = new TileCache(TileCache.resolveRoot(a.cache));
        final TileSource tiles = CachedTileSource.cacheOnly(cache, job.provider());
        final TilePlan.Plan plan = new Composer().plan(job);
        final TilePlan.Inventory inv = TilePlan.inventory(plan, tiles);

        if (a.json) {
            System.out.printf(Locale.ROOT,
                    "{\"needed\":%d,\"cached\":%d,\"empty\":%d,\"missing\":%d,\"sheets\":%d,\"placements\":%d}%n",
                    inv.needed(), inv.cached(), inv.empty(), inv.missing().size(),
                    plan.layout().count(), plan.placements());
            return exitOf(job, inv);
        }
        System.out.printf(Locale.ROOT, "%s %d sheet(s), %,d tile placements, %,d distinct tiles%n",
                PREFIX, plan.layout().count(), plan.placements(), plan.distinctKeys().size());
        System.out.println(inv.describe());
        return exitOf(job, inv);
    }

    /**
     * What a count exits with: a print with nothing real in it is refused as the export would
     * refuse it under every policy – exit 1 when its every tile does not exist, and 2, a refused
     * job, when it has no tile at all; otherwise 4 while tiles are missing, and 0.
     */
    private static int exitOf(final @NotNull ComposeJob job, final TilePlan.@NotNull Inventory inv)
            throws NothingThereException {
        Composer.refuseNothingThere(job, inv);
        return inv.isComplete() ? 0 : 4;
    }

    private static int export(final @NotNull Args a) throws Exception {
        final ComposeJob job = job(a);
        final TileCache cache = new TileCache(TileCache.resolveRoot(a.cache));
        final TileSource tiles = CachedTileSource.cacheOnly(cache, job.provider());

        if (a.dryRun) {
            final TilePlan.Plan plan = new Composer().plan(job);
            final TilePlan.Inventory inv = TilePlan.inventory(plan, tiles);
            System.out.printf(Locale.ROOT, "%s dry run: %d sheet(s), %,d placements, %,d distinct tiles%n",
                    PREFIX, plan.layout().count(), plan.placements(), plan.distinctKeys().size());
            System.out.println(inv.describe());
            return exitOf(job, inv);
        }

        final ConsoleProgress progress = new ConsoleProgress(PREFIX);
        final Composer.ExportReport report = new Composer().export(job, tiles, a.bandTiles, progress);
        if (report.proof()) {
            System.out.println(PREFIX + " THIS IS A PROOF - missing tiles are marked. Do not print it.");
        }
        System.out.println(PREFIX + " " + report.summary());
        for (final Path f : report.files()) {
            System.out.println("    " + f);
        }
        return 0;
    }

    private static int sample(final @NotNull Args a) throws Exception {
        if (a.out == null || a.bbox == null) {
            throw new IllegalArgumentException("sample needs --out and --bbox");
        }
        final WtfFile file = WtfFile.sample(
                a.provider == null ? "mapy-com" : a.provider,
                a.theme == null ? "outdoor" : a.theme,
                a.bbox.west(), a.bbox.south(), a.bbox.east(), a.bbox.north(),
                a.zoom == null ? 15 : a.zoom,
                a.widthMm == null ? 5000 : a.widthMm);
        final Path out = Path.of(a.out);
        file.write(out);
        System.out.println(PREFIX + " wrote " + out);
        return 0;
    }

    private static @NotNull ComposeJob job(final @NotNull Args a) throws Exception {
        if (a.project == null) {
            throw new IllegalArgumentException("a .wtf file is required");
        }
        final Path project = Path.of(a.project);
        final WtfFile file = WtfFile.read(project);
        // The user's own file merged in by default, as the window does; --providers names another
        final ProviderCatalog catalog = ProviderCatalog.load(
                a.providers != null ? a.providers : ProviderStore.defaultFile());
        // Said, not acted on. The editor refuses a file whose area no longer follows from its
        // subject, because a project whose screen disagrees with its own settings is a bad thing
        // to edit. This end only prints, and what it prints is what the file states – so the
        // disagreement is worth a line on stderr and nothing more.
        final WtfFile.AreaCheck check = file.checkArea();
        if (check.claimed() && !check.reproduces()) {
            System.err.println(PREFIX + " warning: this project says how its area was worked out,"
                    + " but working it out again gives a shape up to "
                    + Math.round(check.deviationMeters()) + " m away.");
            System.err.println(PREFIX + " printing the area the file states.");
        }
        final Path defaultOut = a.out != null ? Path.of(a.out)
                : project.toAbsolutePath().getParent().resolve("out");
        ComposeJob job = file.toJob(catalog, defaultOut);
        if (a.out != null || a.missing != null) {
            job = new ComposeJob(job.fillShape(), job.subject(), job.provider(), job.themeId(), job.lang(),
                    job.regions(), job.print(), job.medium(), job.overlapMm(),
                    job.exactArea(), job.fadeFrom(), job.backgroundColor(),
                    a.missing != null ? a.missing : job.missingTiles(),
                    job.formats(), job.grouping(),
                    a.out != null ? Path.of(a.out) : job.outputDir(), job.baseName(), job.marks(),
                    job.words());
        }
        return job;
    }

    // -------------------------------------------------------------------------------------------

    private static final class Args {
        @Nullable String project, out, provider, theme;
        @Nullable Path cache, providers;
        @Nullable LonLatBounds bbox;
        @Nullable Integer zoom;
        @Nullable Double widthMm;
        @Nullable MissingTilePolicy missing;
        /** Null without {@code --band-tiles}, for the engine's own band, as the studio's. */
        @Nullable Integer bandTiles;
        boolean json, dryRun;

        static @NotNull Args parse(final String[] args) {
            final Args a = new Args();
            for (int i = 0; i < args.length; i++) {
                final String s = args[i];
                if (!s.startsWith("--")) {
                    a.project = s;
                    continue;
                }
                switch (s) {
                    case "--cache" -> a.cache = Path.of(need(args, ++i, s));
                    case "--providers" -> a.providers = Path.of(need(args, ++i, s));
                    case "--out" -> a.out = need(args, ++i, s);
                    case "--provider" -> a.provider = need(args, ++i, s);
                    case "--theme" -> a.theme = need(args, ++i, s);
                    case "--zoom" -> a.zoom = Integer.parseInt(need(args, ++i, s));
                    case "--width" -> a.widthMm = Double.parseDouble(need(args, ++i, s));
                    case "--bbox" -> a.bbox = parseBbox(need(args, ++i, s));
                    case "--band-tiles" -> a.bandTiles = Integer.parseInt(need(args, ++i, s));
                    case "--json" -> a.json = true;
                    case "--dry-run" -> a.dryRun = true;
                    case "--missing" -> a.missing = MissingTilePolicy.valueOf(
                            need(args, ++i, s).toUpperCase(Locale.ROOT));
                    default -> throw new IllegalArgumentException("unknown option: " + s);
                }
            }
            return a;
        }

        private static String need(final String[] args, final int i, final String what) {
            if (i >= args.length) {
                throw new IllegalArgumentException(what + " needs a value");
            }
            return args[i];
        }

        /** Always {@code Locale.ROOT}: a decimal comma on a command line would be chaos. */
        private static @NotNull LonLatBounds parseBbox(final @NotNull String s) {
            final String[] p = s.split(",");
            if (p.length != 4) {
                throw new IllegalArgumentException("--bbox wants W,S,E,N in degrees, got: " + s);
            }
            return new LonLatBounds(Double.parseDouble(p[0].trim()), Double.parseDouble(p[1].trim()),
                    Double.parseDouble(p[2].trim()), Double.parseDouble(p[3].trim()));
        }
    }
}
