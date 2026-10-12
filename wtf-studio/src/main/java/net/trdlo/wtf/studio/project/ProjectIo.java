package net.trdlo.wtf.studio.project;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.locationtech.jts.geom.Geometry;
import net.trdlo.wtf.compose.*;
import net.trdlo.wtf.studio.I18n;
import net.trdlo.wtf.studio.Units;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The {@code .wtf} file and the model, in both directions.
 * <p>
 * The model never writes JSON itself and {@link WtfFile} never sees a JavaFX property: this
 * class is the only place that knows both, which is what lets the file format be tested without a
 * display and the model be changed without a migration.
 */
public final class ProjectIo {

    public static final String EXTENSION = ".wtf";

    private ProjectIo() {
    }

    // -------------------------------------------------------------------------------------------
    // Model -> file
    // -------------------------------------------------------------------------------------------

    public static @NotNull WtfFile toFile(final @NotNull ProjectModel m) {
        m.settleArea();
        // What the file calls the area is what gets printed, and the model holds exactly that,
        // worked out from the subject or from the Custom shape. Not the built job's, which a
        // project with no provider chosen does not have, so that it too can be saved. A project
        // with no area yet is saved without one: the paper, the map and the zooms are worth keeping
        // before there is anything to print.
        final Geometry area = m.areaProperty().get();
        final Provider p = m.providerProperty().get();
        final Provider.Theme t = m.themeProperty().get();
        if (p == null || t == null) {
            throw new ProjectRefusal(I18n.get("project.save.noProvider"));
        }
        final Geometry subject = m.subjectProperty().get();
        final boolean custom = m.areaSourceProperty().get() == ProjectModel.AreaSource.CUSTOM;

        return new WtfFile(
                WtfFile.FORMAT,
                blankToNull(m.titleProperty().get()), blankToNull(m.notesProperty().get()),
                Instant.now().toString(),
                new WtfFile.Source(p.id(), t.id(), m.mapLangProperty().get()),
                area == null ? null
                        : WtfFile.multiRingOf(area,
                        Shapes.isRectangle(area) ? "rectangle" : "polygon",
                        custom ? "custom" : "subject"),
                subject == null ? null
                        : WtfFile.multiRingOf(subject, null, m.subjectOriginProperty().get()),
                refineOf(m),
                regionsJson(m, subject != null),
                printJson(m),
                mediumJson(m),
                new WtfFile.ExportJson(
                        m.formats().stream().sorted().toList(),
                        m.groupingProperty().get(),
                        m.outputDirProperty().get() == null ? null
                                : m.outputDirProperty().get().toString(),
                        m.baseNameProperty().get(),
                        m.missingTilesProperty().get(),
                        m.creditOnPrintProperty().get(),
                        m.scaleOnPrintProperty().get() ? m.scaleMarkProperty().get() : null));
    }

    /**
     * How the area is worked out: from the subject with the fit, or from the Custom shape, grown
     * and faded either way – and nothing for a Custom area with no shape yet, which has nothing to
     * grow, and which a file says by naming neither.
     */
    private static WtfFile.@NotNull Refine refineOf(final @NotNull ProjectModel m) {
        final Boolean fade = m.areaFadeProperty().get() ? Boolean.TRUE : null;
        if (m.areaSourceProperty().get() == ProjectModel.AreaSource.FROM_SUBJECT) {
            return new WtfFile.Refine(null, m.simplifyProperty().get(), m.bufferValueProperty().get(), fade);
        }
        final Geometry custom = m.customProperty().get();
        return custom == null ? WtfFile.Refine.NONE
                : new WtfFile.Refine(WtfFile.multiRingOf(custom, null, m.customOriginProperty().get()), null,
                m.bufferValueProperty().get(), fade);
    }

    /**
     * The size that gets printed, which is what every reader of the file takes – and, while the
     * print is sized in sheets, at a DPI or at a scale, the size step's own answer set aside beside it.
     * <p>
     * <b>Not the mode last chosen.</b> Sized in sheets, at a DPI or at a scale, the print is that width or
     * height whatever the size step still says, and writing that mode beside it would make the
     * command line print another size, or refuse: "fill a box" with only a width is no box.
     */
    private static WtfFile.@NotNull PrintJson printJson(final @NotNull ProjectModel m) {
        final PrintSize printed = m.printSize();
        final ProjectModel.SizeBy by = m.sizeByProperty().get();
        final WtfFile.SizeJson setAside = by != ProjectModel.SizeBy.MILLIMETERS
                ? WtfFile.SizeJson.of(new PrintSize(m.printModeProperty().get(),
                m.printWidthMmProperty().get(), m.printHeightMmProperty().get()))
                : null;
        return new WtfFile.PrintJson(printed.mode(), printed.widthOrNull(), printed.heightOrNull(),
                hex(m.backgroundRgbProperty().get()), setAside,
                by == ProjectModel.SizeBy.DPI ? m.dpiProperty().get() : null,
                by == ProjectModel.SizeBy.SCALE ? m.mapScaleProperty().get() : null,
                m.areaExactProperty().get() ? Boolean.TRUE : null);
    }

    private static WtfFile.@NotNull RegionsJson regionsJson(final @NotNull ProjectModel m,
                                                                 final boolean hasSubject) {
        final Regions r = m.regions(hasSubject);
        return new WtfFile.RegionsJson(r.zoom(), r.scale() == 1 ? null : r.scale(),
                new WtfFile.RegionJson(r.inside().drawn(), WtfFile.EffectJson.of(r.inside().effect())),
                new WtfFile.RegionJson(r.outside().drawn(), WtfFile.EffectJson.of(r.outside().effect())),
                // Whenever there is a subject, stroked or not: the color is the subject's on the map
                // too, and a project saved with the stroke off lost it
                hasSubject ? new WtfFile.StrokeJson(m.boundaryStrokedProperty().get(),
                        m.boundaryWidthMmProperty().get(), hex(m.boundaryRgbProperty().get())) : null);
    }

    private static WtfFile.@NotNull MediumJson mediumJson(final @NotNull ProjectModel m) {
        final boolean bySheets = m.sizeByProperty().get() == ProjectModel.SizeBy.SHEETS;
        // The axis the print is counted along: on a roll its strips', whichever way sheets were
        // counted before
        final Integer across = bySheets && m.countsAcross() ? m.sheetCountProperty().get() : null;
        final Integer down = bySheets && !m.countsAcross() ? m.sheetCountProperty().get() : null;

        if (m.mediumKindProperty().get() == ProjectModel.MediumKind.ROLL) {
            return new WtfFile.MediumJson(WtfFile.MediumJson.Kind.ROLL, m.rollWidthMmProperty().get(),
                    m.stripAxisProperty().get(), null, null, null, m.overlapMmProperty().get(),
                    m.edgeMmProperty().get(), across, down);
        }
        final Medium.Paper paper = m.paperProperty().get();
        return new WtfFile.MediumJson(WtfFile.MediumJson.Kind.SHEET, null, null,
                paper.widthMm(), paper.heightMm(), m.orientationProperty().get(), m.overlapMmProperty().get(),
                m.edgeMmProperty().get(), across, down);
    }

    public static void save(final @NotNull ProjectModel m, final @NotNull Path file) throws IOException {
        toFile(m).write(file);
        m.fileProperty().set(file);
        m.history().markSaved();
    }

    // -------------------------------------------------------------------------------------------
    // File -> model
    // -------------------------------------------------------------------------------------------

    /**
     * Replaces the project with a file's: all of it, or – when the file is refused – none of it.
     * <p>
     * <b>Read whole, then swapped.</b> The file is read into a model of its own, and the project
     * that was open is replaced only once that model has come out whole
     * ({@link ProjectModel#replaceWith}). A file refused halfway leaves the open project, its
     * history and its file name as they were.
     * <p>
     * <b>What the file does not say is what a new project has</b> – the fit of a Custom area, the
     * sheet count of a print sized in millimeters, the roll of a print on sheets – and not whatever
     * the project open before happened to hold. The one exception is the
     * paper and the way it is turned, which a print on a roll does not name: the window remembers
     * the paper last used from every change to it, and a new project's A4 arriving would be
     * remembered as though somebody had chosen it. Nor is a map language it does not name a new
     * project's, which is the system's: it is the provider's own (D40), as the command line reads it.
     *
     * @return the warnings worth showing – a provider that is no longer installed, a theme that
     *         has gone away. A project is a thing you can mail somebody, so it opening on their
     *         machine with a note beats it refusing to open at all.
     * @throws IOException              when the file cannot be read, is not JSON, or holds a value
     *                                  that its field does not take; see {@link WtfFile#read}
     * @throws ProjectRefusal           when the editor's own checks refuse the file, said from the
     *                                  bundle; the engine's refuse with a {@link net.trdlo.wtf.tiles.Refusal},
     *                                  which the error dialog says from the bundle. Both get the
     *                                  refusal's headline; nothing else does
     */
    public static @NotNull List<String> load(final @NotNull ProjectModel m, final @NotNull Path path,
                                             final @NotNull ProviderCatalog catalog) throws IOException {
        final List<String> warnings = replace(m, WtfFile.read(path), name(path), catalog);
        m.fileProperty().set(path);
        return warnings;
    }

    /**
     * Opens an example the studio bundles as a new project: read and checked as {@link #load} reads
     * a file, then on no file, with nothing unsaved and nothing to undo, and titled with the name its
     * card on the Start page has.
     * <p>
     * <b>New, not recovered.</b> An example as it opens is in the studio's jar, as a new project is
     * in New, so nothing about it is unsaved until somebody changes it, and closing an untouched one
     * asks nothing. Having no file, Save asks for a name, and nothing writes over the example.
     *
     * @return the warnings worth showing, as {@link #load} has them – none, for the examples as they
     *         ship
     */
    public static @NotNull List<String> openExample(final @NotNull ProjectModel m, final @NotNull Example example,
                                                    final @NotNull ProviderCatalog catalog) throws IOException {
        final List<String> warnings = replace(m, WtfFile.read(example.read(), example.fileName()),
                example.fileName(), catalog);
        m.fileProperty().set(null);
        // In the interface's language, so Save offers it as the file's name; set as where the
        // project starts, which leaves nothing to undo
        m.startOver(() -> m.titleProperty().set(example.title()));
        return warnings;
    }

    /**
     * Replaces the project with a project file's – read whole into a model of its own, checked, and
     * swapped in only once it came out whole – leaving the project's file as it was.
     *
     * @param name the file's name, as a refusal says it
     */
    private static @NotNull List<String> replace(final @NotNull ProjectModel m, final @NotNull WtfFile f,
                                                 final @NotNull String name,
                                                 final @NotNull ProviderCatalog catalog) {
        final WtfFile.AreaCheck check = f.checkArea();
        if (check.claimed() && !check.reproduces()) {
            throw new ProjectRefusal(doesNotReproduce(name, f, check));
        }

        final List<String> warnings = new ArrayList<>();
        final ProjectModel read = new ProjectModel();
        read.startOver(() -> {
            // The paper in use, which a roll does not name – see above
            read.paperProperty().set(m.paperProperty().get());
            read.orientationProperty().set(m.orientationProperty().get());
            fill(read, f, catalog, warnings);
        });
        // A count of sheets is a note, as the area's working-out is, and it has to still come to the
        // size every reader prints. Asked of the model just filled, so the arithmetic is the one the
        // editor would print with – and before the swap, so a refusal leaves the open project alone.
        if (read.sizeByProperty().get() == ProjectModel.SizeBy.SHEETS && !counts(read.printSize(), f.print())) {
            throw new ProjectRefusal(doesNotCount(name, f, read.printSize()));
        }
        // A DPI is the same kind of note, checked once there is an area for it to come to a width of
        final Integer dpi = f.print().dpi();
        if (dpi != null && (dpi <= 0
                || read.areaProperty().get() != null && !counts(read.printSize(), f.print()))) {
            throw new ProjectRefusal(I18n.format("project.refused.size.dpi", name, dpi,
                    read.zoomProperty().get(), dpi <= 0 ? "–" : Units.current().length(read.printSize().widthMm(), 0),
                    blockOf(f.print())));
        }
        // And a scale
        final Integer scale = f.print().mapScale();
        if (scale != null && (scale <= 0
                || read.areaProperty().get() != null && !counts(read.printSize(), f.print()))) {
            throw new ProjectRefusal(I18n.format("project.refused.size.scale", name,
                    I18n.format("medium.scale.ratio", I18n.integer(scale)),
                    scale <= 0 ? "–" : Units.current().length(read.printSize().widthMm(), 0),
                    blockOf(f.print())));
        }
        m.replaceWith(read);
        return warnings;
    }

    /**
     * Opens a recovery copy of unsaved work: the project it holds, on the file it is a copy of, and
     * unsaved – which is what it was when its window went.
     * <p>
     * Read as any project is read, checks and warnings and all, since the copy is the file a save of
     * it would have written. Then the file is the project's own again, or none for one never saved,
     * so Save writes where the work belongs; and nothing is on disk as it stands, so the title says
     * so and closing asks.
     *
     * @param copy the recovery copy
     * @param of   the file it is a copy of, or null for a project that was never saved
     * @return the warnings worth showing, as {@link #load} has them
     */
    public static @NotNull List<String> recover(final @NotNull ProjectModel m, final @NotNull Path copy,
                                                final @Nullable Path of, final @NotNull ProviderCatalog catalog)
            throws IOException {
        final List<String> warnings = load(m, copy, catalog);
        m.fileProperty().set(of);
        m.history().markUnsaved();
        return warnings;
    }

    /** Writes a file's project into a new model, in an order its listeners can follow. */
    private static void fill(final @NotNull ProjectModel m, final @NotNull WtfFile f,
                             final @NotNull ProviderCatalog catalog, final @NotNull List<String> warnings) {
        // find, not the visible ones: a provider the file names opens even hidden. The one it falls
        // back to is one the pickers offer, and a hidden one only when there is nothing else.
        final Provider p = catalog.find(f.source().provider()).orElseGet(() -> {
            final Provider instead = catalog.visible().stream().findFirst()
                    .orElseGet(() -> catalog.all().iterator().next());
            warnings.add(I18n.format("project.warn.provider", f.source().provider(), instead.displayName()));
            return instead;
        });
        m.providerProperty().set(p);
        m.themeProperty().set(p.theme(f.source().theme()).orElseGet(() -> {
            warnings.add(I18n.format("project.warn.map", f.source().theme(), p.displayName(),
                    p.themes().getFirst().displayName()));
            return p.themes().getFirst();
        }));
        // The file's language, or the provider's own where it names none (D40) – as the command line
        // reads it, so the window prints what it prints. Not the system's, nor the one last used
        // with the provider, which the window brings back when the provider is written: the
        // language comes after the provider when this model is put in (ProjectModel.put), and wins
        m.mapLangProperty().set(f.source().langFor(p));

        m.titleProperty().set(f.title() == null ? "" : f.title());
        m.notesProperty().set(f.notes() == null ? "" : f.notes());

        // How the area is worked out goes in before what it is worked out from. A new model works it
        // out on every change, and with no worker behind it, on the spot: under "the outline itself"
        // or a Custom area each setting written after the shape would be one more union of all of it.
        final WtfFile.Refine r = f.refine();
        final Geometry subject = f.subjectGeometry();
        final Geometry custom = f.customGeometry();
        final Geometry stated = f.areaGeometry();
        // A fit with no subject is still the choice while there is no area either – a project saved
        // before its subject was picked, whose next subject gets its area; beside an area, it is one
        // nothing can check, and the area stands, as it does in a file that names neither
        final boolean fromSubject = custom == null && r.simplify() != null && (subject != null || stated == null);
        final boolean grown = fromSubject || custom != null;
        if (r.simplify() != null) {
            m.simplifyProperty().set(r.simplify());
        }
        m.bufferValueProperty().set(grown ? r.growth() : 0);
        m.areaFadeProperty().set(grown && Boolean.TRUE.equals(r.fade()));
        m.areaExactProperty().set(Boolean.TRUE.equals(f.print().exactArea()));
        // The source before the shapes, so that the Custom shape arrives as the one chosen. No area
        // is written: it is worked out from them as they arrive, and load checked that the file
        // agrees with it. A file that names neither is a Custom area of the shape it states, grown
        // by nothing, so that it prints as it stands.
        m.areaSourceProperty().set(fromSubject
                ? ProjectModel.AreaSource.FROM_SUBJECT : ProjectModel.AreaSource.CUSTOM);
        m.subjectProperty().set(subject);
        m.subjectOriginProperty().set(f.subject() == null ? null : f.subject().source());
        if (!fromSubject) {
            final WtfFile.MultiRing given = r.custom();
            m.customProperty().set(custom != null ? custom : stated);
            m.customOriginProperty().set(given == null ? null : given.source());
        }

        loadMedium(m, f.medium());
        loadPrint(m, f.print());
        loadRegions(m, f.regions());
        loadExport(m, f.export());
    }

    private static void loadMedium(final @NotNull ProjectModel m, final WtfFile.@NotNull MediumJson j) {
        if (j.kind() == WtfFile.MediumJson.Kind.ROLL) {
            m.mediumKindProperty().set(ProjectModel.MediumKind.ROLL);
            if (j.rollWidthMm() != null) {
                m.rollWidthMmProperty().set(j.rollWidthMm());
            }
            m.stripAxisProperty().set(j.stripAxis() == null ? Medium.Axis.VERTICAL : j.stripAxis());
        } else {
            m.mediumKindProperty().set(ProjectModel.MediumKind.SHEET);
            // A known paper when it is one, so the combo shows "A0"; else the file's own size. The
            // list cannot offer that, but the command line prints it, and so must the editor,
            // rather than keep whatever paper was open without a word.
            m.paperProperty().set(Medium.PAPER_SIZES.stream()
                    .filter(p -> near(p.widthMm(), j.sheetWidthMm()) && near(p.heightMm(), j.sheetHeightMm()))
                    .findFirst()
                    .orElseGet(() -> new Medium.Paper(I18n.get("medium.paper.custom"),
                            j.sheetWidthMm(), j.sheetHeightMm())));
            m.orientationProperty().set(j.orientation() == null ? Medium.Orientation.PORTRAIT : j.orientation());
        }
        m.overlapMmProperty().set(j.overlapMm());
        m.edgeMmProperty().set(j.edgeMm());

        // A file that names a count was sized in sheets; one that names neither, in millimeters.
        final Integer across = j.sheetsAcross();
        final Integer down = j.sheetsDown();
        if (across != null || down != null) {
            m.sizeByProperty().set(ProjectModel.SizeBy.SHEETS);
            m.countAcrossProperty().set(across != null);
            m.sheetCountProperty().set(across != null ? across : down);
        } else {
            m.sizeByProperty().set(ProjectModel.SizeBy.MILLIMETERS);
        }
    }

    /**
     * The size step's settings. Sized in millimeters they are the print block itself. Sized in
     * sheets, at a DPI or at a scale, the block is what those come to and the settings are the ones
     * set aside beside it – or, in a file that has none, the block after all, which at least leaves
     * the same print when somebody goes back to millimeters. A file that names a DPI or a scale was
     * sized at it.
     */
    private static void loadPrint(final @NotNull ProjectModel m, final WtfFile.@NotNull PrintJson j) {
        if (j.dpi() != null) {
            m.sizeByProperty().set(ProjectModel.SizeBy.DPI);
            m.dpiProperty().set(j.dpi());
        }
        if (j.mapScale() != null) {
            m.sizeByProperty().set(ProjectModel.SizeBy.SCALE);
            m.mapScaleProperty().set(j.mapScale());
        }
        final boolean noted = m.sizeByProperty().get() != ProjectModel.SizeBy.MILLIMETERS;
        final WtfFile.SizeJson size = noted && j.setAside() != null ? j.setAside()
                : new WtfFile.SizeJson(j.sizeMode(), j.widthMm(), j.heightMm());
        m.printModeProperty().set(size.sizeMode());
        if (size.widthMm() != null) {
            m.printWidthMmProperty().set(size.widthMm());
        }
        if (size.heightMm() != null) {
            m.printHeightMmProperty().set(size.heightMm());
        }
        m.backgroundRgbProperty().set(parseHex(j.backgroundColor(), 0xFFFFFF));
    }

    private static void loadRegions(final @NotNull ProjectModel m, final WtfFile.@NotNull RegionsJson j) {
        m.zoomProperty().set(j.zoom());
        m.scaleProperty().set(j.scale() == null ? 1 : j.scale());
        m.insideEffectProperty().set(j.inside().effect().toEffect());
        m.insideDrawnProperty().set(j.inside().drawn());
        m.outsideDrawnProperty().set(j.outside().drawn());
        // A file says what the two regions are; whether they are treated differently is asked of
        // them rather than stored, so the two can never disagree
        final TileEffect outside = j.outside().effect().toEffect();
        m.outsideDiffersProperty().set(!outside.equals(j.inside().effect().toEffect()));
        m.outsideEffectProperty().set(outside);
        final WtfFile.StrokeJson outline = j.boundaryStroke();
        m.boundaryStrokedProperty().set(outline != null && outline.drawn());
        if (outline != null) {
            // Drawn or not: the color is the subject's on the map as well
            m.boundaryWidthMmProperty().set(outline.widthMm());
            m.boundaryRgbProperty().set(parseHex(outline.color(), ProjectModel.SUBJECT_RGB));
        }
    }

    private static void loadExport(final @NotNull ProjectModel m, final WtfFile.@NotNull ExportJson j) {
        m.formats().clear();
        m.formats().addAll(j.formats());
        if (m.formats().isEmpty()) {
            m.formats().add(ComposeJob.OutputFormat.PDF);
        }
        m.groupingProperty().set(j.grouping());
        m.outputDirProperty().set(j.outputDir() == null ? null : Path.of(j.outputDir()));
        m.baseNameProperty().set(j.baseName());
        m.creditOnPrintProperty().set(j.credit());
        m.scaleOnPrintProperty().set(j.scale() != null);
        if (j.scale() != null) {
            m.scaleMarkProperty().set(j.scale());
        }
        m.missingTilesProperty().set(j.missingTiles() == null ? MissingTilePolicy.REFUSE : j.missingTiles());
    }

    // -------------------------------------------------------------------------------------------

    private static boolean near(final double a, final double b) {
        return Math.abs(a - b) < 0.5;
    }

    /**
     * Why a file that contradicts itself is not opened, and the one line that opens it anyway.
     * <p>
     * <b>Refusing needs a way out, or it is just a dead end.</b> Nothing in the application can
     * offer one – the file never got as far as the model – so the message has to. Deleting the fit
     * or the Custom shape leaves the stored area untouched, and the project opens as a Custom area
     * of that shape, grown by nothing, which loses the note about where the area came from and
     * nothing else.
     * <p>
     * This is a deliberate exception to the rule the javadoc on {@link #load} states: a project is
     * something you can mail somebody, so opening it with a note beats refusing. That rule is
     * about a machine that differs – a provider not installed, a theme gone away. This is a file
     * that disagrees with itself, and a project whose screen does not match its own settings is
     * worse than one that will not open.
     */
    private static @NotNull String doesNotReproduce(final @NotNull String name,
                                                    final @NotNull WtfFile f,
                                                    final WtfFile.@NotNull AreaCheck check) {
        final WtfFile.Refine r = f.refine();
        // In the words the area step uses for the same settings: the Custom shape, or the fit
        final Units u = Units.current();
        final Shapes.Simplify fit = r.simplify();
        final String from = r.custom() != null ? I18n.get("area.source.custom")
                : fit == null ? "" : I18n.get("area.fit." + fit.name());
        final String how = r.growth() == 0 ? from
                : I18n.format("area.summary.grown", from, u.ground(r.growth()));
        return I18n.format("project.refused.area", name, how, u.longLength(check.deviationMeters() * 1000),
                across(check.stored()), across(check.recomputed()));
    }

    /**
     * How far apart the size a count of sheets comes to and the size stated beside it may be: a
     * hundredth of a millimeter. Both come out of the same arithmetic, so anything more is a file
     * edited by hand, or written before that arithmetic changed.
     */
    private static final double COUNT_TOLERANCE_MM = 0.01;

    /** Whether the size a count of sheets comes to is the one the print block states. */
    private static boolean counts(final @NotNull PrintSize counted, final WtfFile.@NotNull PrintJson stated) {
        if (stated.sizeMode() != counted.mode()) {
            return false;
        }
        // A count fixes one side, the one its mode names; the other follows from the area
        final boolean across = counted.mode() == PrintSize.Mode.FIT_WIDTH;
        final Double length = across ? stated.widthMm() : stated.heightMm();
        return length != null
                && Math.abs(length - (across ? counted.widthMm() : counted.heightMm())) < COUNT_TOLERANCE_MM;
    }

    /**
     * Why a file whose count of sheets no longer comes to the size it states is not opened, and the
     * one line to delete to open it anyway – for the reason {@link #doesNotReproduce} gives: a file
     * that disagrees with itself would open showing one print while the command line made another.
     */
    private static @NotNull String doesNotCount(final @NotNull String name, final @NotNull WtfFile f,
                                                final @NotNull PrintSize counted) {
        final boolean across = f.medium().sheetsAcross() != null;
        final Integer count = across ? f.medium().sheetsAcross() : f.medium().sheetsDown();
        final int n = count == null ? 0 : count;
        final double length = counted.mode() == PrintSize.Mode.FIT_WIDTH ? counted.widthMm() : counted.heightMm();
        return I18n.format(across ? "project.refused.size.across" : "project.refused.size.down",
                name, n, Units.current().length(length, 0), blockOf(f.print()));
    }

    /** The print block as the file spells it, since that is what the reader is to look for. */
    private static @NotNull String blockOf(final WtfFile.@NotNull PrintJson stated) {
        return stated.sizeMode()
                + (stated.widthMm() == null ? "" : String.format(Locale.ROOT, ", widthMm %.0f", stated.widthMm()))
                + (stated.heightMm() == null ? "" : String.format(Locale.ROOT, ", heightMm %.0f", stated.heightMm()));
    }

    /** "512.0 × 389.0 km", or the same in miles – the size of a shape, for somebody comparing two. */
    private static @NotNull String across(final net.trdlo.wtf.tiles.@Nullable LonLatBounds b) {
        if (b == null) {
            return I18n.get("project.refused.nothing");
        }
        final double midLat = (b.north() + b.south()) / 2;
        final double w = (b.east() - b.west()) * Shapes.METERS_PER_DEGREE_LATITUDE
                * Math.cos(Math.toRadians(midLat));
        final double h = (b.north() - b.south()) * Shapes.METERS_PER_DEGREE_LATITUDE;
        final Units u = Units.current();
        return I18n.format("area.ground", u.groundNumber(w), u.groundNumber(h), u.groundSymbol());
    }

    private static @NotNull String name(final @NotNull Path path) {
        return String.valueOf(path.getFileName());
    }

    private static @Nullable String blankToNull(final @Nullable String s) {
        return s == null || s.isBlank() ? null : s;
    }

    public static @NotNull String hex(final int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    private static int parseHex(final @Nullable String s, final int fallback) {
        if (s == null || s.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(s.startsWith("#") ? s.substring(1) : s, 16) & 0xFFFFFF;
        } catch (final NumberFormatException ex) {
            return fallback;
        }
    }
}
