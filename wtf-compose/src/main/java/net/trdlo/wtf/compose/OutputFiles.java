package net.trdlo.wtf.compose;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Putting finished files where they belong, and saying so plainly when something holds one open.
 * <p>
 * <b>The usual culprit is the PDF viewer.</b> Export, look at it, change a number, export again –
 * and the viewer still has the last file open. On Windows a file another program has open cannot be
 * replaced, and an export that found out only at the rename would fail at the very end, after
 * minutes of work, with a stack trace in the system's own language. So the files an export will
 * replace are tried before any work starts, and the rename at the end reports the same thing if a
 * file was opened in between. And the files the run replaces are set aside as it starts, so a
 * canceled run cannot pass for a whole one – with the print files of its name it will not
 * replace, so a run of fewer sheets cannot either – and the last whole export stays aside however
 * many runs are canceled after it.
 */
final class OutputFiles {

    private static final Logger log = LoggerFactory.getLogger(OutputFiles.class);

    private OutputFiles() {
    }

    /**
     * Tries every file the export will replace, before anything is written.
     * <p>
     * Opening for writing without truncating is the question Windows answers the way the final
     * rename would: a viewer that shares the file for reading only refuses it. Files that do not
     * exist yet are fine.
     */
    static void checkReplaceable(final @NotNull Collection<Path> targets) throws Composer.FileInUseException {
        for (final Path target : targets) {
            if (!Files.exists(target)) {
                continue;
            }
            try (FileChannel ignored = FileChannel.open(target, StandardOpenOption.WRITE)) {
                // opened, so it can be replaced
            } catch (final IOException ex) {
                throw new Composer.FileInUseException(target, ex);
            }
        }
    }

    /** What a file the run replaces is called while the run is on, and after a canceled one. */
    static final String PREVIOUS = ".previous";

    /** The file beside a print that lays it out, after the print's base name. */
    static final String LAYOUT = "-layout.txt";

    /** The file beside a print that credits its map, after the print's base name. */
    static final String ATTRIBUTION = "-attribution.txt";

    /** The one format whose file may hold every sheet, and so may have no sheet number in its name. */
    private static final String PDF = "pdf";

    private static final String PROOF = "-proof";

    /** A sheet's file name before its extension: the base name, the sheet's number, a proof or not. */
    private static final Pattern SHEET = Pattern.compile("(.+)-s\\d{2,}(?:" + PROOF + ")?");

    /** {@code print-s02.pdf.previous} for {@code print-s02.pdf}. */
    static @NotNull Path previousOf(final @NotNull Path target) {
        return target.resolveSibling(target.getFileName() + PREVIOUS);
    }

    /**
     * Every file of this base name that a run sets aside as it starts and clears when it is whole:
     * the files it writes, and every print file of its base name and these extensions in the
     * folder, in place or set aside, whatever sheets and kind of print wrote it.
     * <p>
     * A run that lays out four sheets where the last one laid out six writes over four; without
     * the rest, the last one's fifth and sixth sheets stayed under their names beside a layout that
     * does not mention them, and a folder sent to a print shop carried both prints. So a name
     * counts when it is one this job could write under some layout: the base name, a sheet number
     * – which a PNG or TIFF always has, and a PDF has unless it is the whole print in one file –
     * {@code -proof} or not, and the extension. Exactly that and nothing looser: another project's
     * {@code wall-east-s01.pdf} beside this {@code wall}'s prints is not this job's, and setting it
     * aside would be a new way to lose somebody's file.
     * <p>
     * <b>Nor is a name another project could have written itself</b> ({@link #claimedByAnother}):
     * {@code wall-s01.pdf} is the first sheet of {@code wall} and the whole document of a project
     * named {@code wall-s01}, {@code wall-proof.pdf} a proof of {@code wall} and the document of
     * {@code wall-proof}. The name cannot tell them apart, so the folder does: the file is the other
     * project's when the other project's layout or attribution is there, in place or set aside, and
     * this run leaves it and its {@code .previous} alone. One of the files this run writes is still
     * written over when its sheet is done, as two projects of one name would be.
     *
     * @param outputs every file the run writes, in the order it writes them
     * @return the outputs first, in their order, then the other print files of the name, sorted
     */
    static @NotNull List<Path> filesOf(final @NotNull Path dir, final @NotNull String baseName,
                                       final @NotNull Collection<String> extensions,
                                       final @NotNull Collection<Path> outputs) throws IOException {
        final Set<String> there = namesIn(dir);
        final Set<Path> files = new LinkedHashSet<>();
        for (final Path output : outputs) {
            if (!claimedByAnother(output.getFileName().toString(), baseName, there)) {
                files.add(output);
            }
        }
        if (!extensions.isEmpty()) {
            final Pattern print = printFileName(baseName, extensions);
            final Set<String> found = new TreeSet<>();
            for (final String name : there) {
                final String file = name.endsWith(PREVIOUS)
                        ? name.substring(0, name.length() - PREVIOUS.length()) : name;
                if (print.matcher(file).matches() && !claimedByAnother(file, baseName, there)) {
                    found.add(file);
                }
            }
            for (final String file : found) {
                files.add(dir.resolve(file));
            }
        }
        return new ArrayList<>(files);
    }

    /** The names of the files in the folder, none when there is no folder. */
    private static @NotNull Set<String> namesIn(final @NotNull Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return Set.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toSet());
        }
    }

    /**
     * {@code <base>(-sNN)?(-proof)?.pdf}, and {@code <base>-sNN(-proof)?} with {@code .png} or
     * {@code .tif}: only a PDF gathers every sheet into one file, named without a number.
     */
    static @NotNull Pattern printFileName(final @NotNull String baseName, final @NotNull Collection<String> extensions) {
        final StringJoiner forms = new StringJoiner("|", "(?:", ")");
        for (final String extension : extensions) {
            forms.add((PDF.equals(extension) ? "(?:-s\\d{2,})?" : "-s\\d{2,}") + "(?:" + PROOF + ")?"
                    + Pattern.quote("." + extension));
        }
        return Pattern.compile(Pattern.quote(baseName) + forms);
    }

    /**
     * Whether a print file of this name is another project's: whether a base name other than this
     * one could have written it, and that base name's layout or attribution is in the folder, in
     * place or set aside. A project whose layout and attribution are gone leaves nothing to know
     * its files by, and they are taken for this one's.
     *
     * @param there the names of the files in the folder
     */
    private static boolean claimedByAnother(final @NotNull String file, final @NotNull String baseName,
                                            final @NotNull Set<String> there) {
        for (final String base : basesThatWrite(file)) {
            if (!base.equals(baseName) && (there.contains(base + LAYOUT) || there.contains(base + ATTRIBUTION)
                    || there.contains(base + LAYOUT + PREVIOUS) || there.contains(base + ATTRIBUTION + PREVIOUS))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every base name whose print could have a file of this name: the one before a sheet's number,
     * and for a PDF the whole name, proof or not, as one file of every sheet.
     */
    private static @NotNull Set<String> basesThatWrite(final @NotNull String file) {
        final int dot = file.lastIndexOf('.');
        if (dot < 0) {
            return Set.of();
        }
        final String stem = file.substring(0, dot);
        final Set<String> bases = new LinkedHashSet<>();
        final Matcher sheet = SHEET.matcher(stem);
        if (sheet.matches()) {
            bases.add(sheet.group(1));
        }
        if (PDF.equals(file.substring(dot + 1))) {
            bases.add(stem);
            if (stem.endsWith(PROOF)) {
                bases.add(stem.substring(0, stem.length() - PROOF.length()));
            }
        }
        return bases;
    }

    /**
     * What a run may rename, delete or write over, for {@link #checkReplaceable} to try first: the
     * files it writes, the files it sets aside, and their {@code .previous} files, which a whole
     * run deletes – a viewer showing last time's print as {@code .previous} would otherwise fail the
     * run at its very end, with every file written.
     */
    static @NotNull List<Path> touchedBy(final @NotNull Collection<Path> outputs, final @NotNull Collection<Path> aside) {
        final Set<Path> touched = new LinkedHashSet<>(outputs);
        touched.addAll(aside);
        for (final Path file : aside) {
            touched.add(previousOf(file));
        }
        return new ArrayList<>(touched);
    }

    /**
     * Moves the last export's files out of the way before the run starts, so that a run that is
     * canceled leaves a folder that says so.
     * <p>
     * D26 keeps the sheets a canceled run finished and deletes the one in progress – but without
     * this, the last export's later sheets, layout and attribution would still be there under the
     * same names, and the two halves together would look like one whole print. So every file the
     * run would replace is renamed {@code <name>.previous} as the run starts: a whole run deletes
     * them at its end ({@link #discardAside}), and a canceled run leaves them, under a name nothing
     * prints, beside the sheets it did finish. Nothing is lost by a cancel that a rename cannot
     * bring back.
     * <p>
     * <b>A {@code .previous} file is never replaced.</b> One that is there when a run starts was
     * set aside by a run that did not finish, from the last export that did: the run that set it
     * aside renamed every file of its kind first, so the files of that kind in place were written
     * after – by a run that did not finish either, whatever their names: a run of sheets after a
     * whole document, or of a print after a proof, writes none over what it set aside. They are
     * not the last whole export, and they do not take its place or join it aside: they are
     * deleted, and the {@code .previous} files stay the last whole export's however many runs are
     * canceled after it. A kind is an extension, since a run of PDFs sets no PNG aside and a PNG
     * beside its sheets may be a whole export's; the layout and the attribution are one kind. So a
     * file renamed back from {@code .previous} by hand while others of its kind are still aside is
     * taken for a canceled run's: what is wanted from aside is copied out, or all of it renamed
     * back. And a canceled run that found nothing of a kind to set aside leaves nothing to know its
     * files of that kind by, and they go aside as the last export's, which they are: no whole
     * export of that kind came before them.
     * <p>
     * <b>All or nothing.</b> Every rename is made before any file is deleted, and when one fails –
     * a viewer that lets a file be written but not renamed – those made are put back, the last
     * first, and the run stops with the file named: the folder is as it was. A delete that fails
     * puts the renames back too; what it deleted before was a canceled run's and was going anyway.
     *
     * @param files the run's files ({@link #filesOf}), in place or not
     */
    static void moveAside(final @NotNull Collection<Path> files) throws IOException {
        // The kinds of which the last whole export is aside, and what is in place a canceled run's
        final Set<String> canceled = new HashSet<>();
        for (final Path file : files) {
            if (Files.exists(previousOf(file))) {
                canceled.add(kindOf(file));
            }
        }
        final List<Path> inPlace = files.stream().filter(Files::exists).toList();
        final List<Path> moved = new ArrayList<>();
        for (final Path file : inPlace) {
            if (canceled.contains(kindOf(file))) {
                continue;
            }
            try {
                Files.move(file, previousOf(file));
            } catch (final IOException ex) {
                throw putBack(moved, file, ex);
            }
            moved.add(file);
        }
        for (final Path file : inPlace) {
            if (!canceled.contains(kindOf(file))) {
                continue;
            }
            try {
                Files.deleteIfExists(file);
            } catch (final IOException ex) {
                throw putBack(moved, file, ex);
            }
        }
    }

    /** A file's extension, which is what its kind is to {@link #moveAside}. */
    private static @NotNull String kindOf(final @NotNull Path file) {
        final String name = file.getFileName().toString();
        return name.substring(name.lastIndexOf('.') + 1);
    }

    /**
     * Renames back what {@link #moveAside} renamed, the last first, and says what stopped it: a
     * file another program holds, by name, as {@link #checkReplaceable} would have.
     */
    private static @NotNull IOException putBack(final @NotNull List<Path> moved, final @NotNull Path stuck,
                                                final @NotNull IOException ex) {
        for (int i = moved.size() - 1; i >= 0; i--) {
            final Path file = moved.get(i);
            try {
                Files.move(previousOf(file), file);
            } catch (final IOException back) {
                log.warn("{} could not be put back from {}", file, previousOf(file).getFileName(), back);
                ex.addSuppressed(back);
            }
        }
        return ex instanceof FileSystemException ? new Composer.FileInUseException(stuck, ex) : ex;
    }

    /**
     * After a whole run: every file was replaced, so what was set aside is not wanted – by this run
     * or by one canceled before it, the last whole export's sheets past this run's own included.
     */
    static void discardAside(final @NotNull Collection<Path> targets) throws IOException {
        for (final Path target : targets) {
            Files.deleteIfExists(previousOf(target));
        }
    }

    /**
     * Removes the {@code .part} of a write that failed, whatever failed it – an
     * {@code OutOfMemoryError} as much as an {@code IOException}. Half a file left under a
     * {@code .part} name is a file somebody may yet rename and send to a printer. A removal that
     * fails is added to the failure, which the caller throws on.
     */
    static void discardPart(final @NotNull Path part, final @NotNull Throwable failure) {
        try {
            Files.deleteIfExists(part);
        } catch (final IOException ex) {
            failure.addSuppressed(ex);
        }
    }

    /** Moves a finished {@code .part} into place, removing it again if the target will not budge. */
    static void replace(final @NotNull Path part, final @NotNull Path target) throws IOException {
        try {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (final NoSuchFileException ex) {
            throw ex;
        } catch (final FileSystemException ex) {
            Files.deleteIfExists(part);
            throw new Composer.FileInUseException(target, ex);
        }
    }
}
