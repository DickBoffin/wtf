package net.trdlo.wtf.studio;

/**
 * The entry point the installers use.
 * <p>
 * It exists for one reason: <b>it does not extend {@code Application}</b>. When JavaFX is on the
 * class path rather than the module path, launching a class that extends {@code Application}
 * fails with "JavaFX runtime components are missing", because the launcher checks that the
 * JavaFX modules were resolved as modules. Going through a plain class sidesteps the check, and
 * JavaFX then starts normally.
 * <p>
 * The class path is not a preference here, it is a consequence. Packaging with {@code jpackage}
 * over a module path means {@code jlink}, and {@code jlink} refuses automatic modules – of which
 * this application has four it does not control: {@code jts-core}, {@code pdfbox},
 * {@code pdfbox-io} and {@code fontbox}. So the installers ship a runtime image linked from the
 * JDK's modules and JavaFX's, which are proper named modules ({@code tools/package/package.sh}
 * says why), and put the application's own jars and the rest on the class path beside it. The
 * module declarations still do their job everywhere else: they are what the compiler checks, and
 * they are what keeps JavaFX out of the engine modules.
 * <p>
 * Running from Maven or the IDE uses the module path and {@link WtfApp} directly, so both
 * paths are exercised.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(final String[] args) {
        WtfApp.main(args);
    }
}
