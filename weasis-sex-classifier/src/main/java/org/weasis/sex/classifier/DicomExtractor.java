/*
 * Sex Classification Plugin – LabRoM_IML
 *
 * DicomExtractor: pure-Java DICOM reader — NO dcm4che3 runtime dependency.
 *
 * SC detection reads DICOM binary tags directly (Explicit/Implicit VR LE).
 * Pixel extraction handles: raw bytes, JPEG/JPEG2000 encapsulated data.
 */
package org.weasis.sex.classifier;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects DICOM Secondary Capture files and extracts their images as PNGs.
 *
 * <h3>Pure-Java approach</h3>
 * <p>
 * All DICOM tag parsing uses raw byte reads via {@link RandomAccessFile}
 * so there is no runtime dependency on dcm4che3 or any OSGi-exported class.
 *
 * <h3>Pixel extraction</h3>
 * <p>
 * SC DICOM pixel data is extracted via the bundled {@code dicom_extract_pixels.py}
 * script (pydicom + Pillow). Pillow's libjpeg backend reads JFIF APP0 / Adobe APP14
 * colour-space markers, so YCbCr→RGB conversion is handled correctly without any
 * statistical heuristics that can misfire.
 */
public final class DicomExtractor {

  private static final Logger LOGGER = LoggerFactory.getLogger(DicomExtractor.class);

  // SOPClassUID values that identify Secondary Capture (all multi-frame variants)
  private static final String[] SC_SOP_UIDS = {
      "1.2.840.10008.5.1.4.1.1.7", // Secondary Capture
      "1.2.840.10008.5.1.4.1.1.7.1", // Multi-frame Single Bit SC
      "1.2.840.10008.5.1.4.1.1.7.2", // Multi-frame Grayscale Byte SC
      "1.2.840.10008.5.1.4.1.1.7.3", // Multi-frame Grayscale Word SC
      "1.2.840.10008.5.1.4.1.1.7.4", // Multi-frame True Color SC
  };

  // Also check FMI MediaStorageSOPClassUID (0002,0002) — faster than dataset scan
  private static boolean isScSopUid(String uid) {
    for (String s : SC_SOP_UIDS)
      if (s.equals(uid))
        return true;
    return false;
  }

  private static final byte[] DICOM_MAGIC = { 'D', 'I', 'C', 'M' };

  // --- diagnostic counters (read by SexClassifierAction for error messages) ---
  static volatile int lastTotal = 0;
  static volatile int lastDicom = 0;
  static volatile int lastSc = 0;
  static volatile int lastPixOk = 0;

  private DicomExtractor() {
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Public API
  // ───────────────────────────────────────────────────────────────────────────

  /** Returns {@code true} if the file has a valid DICOM magic at offset 128. */
  public static boolean isDicom(File file) {
    if (!file.isFile() || file.length() < 132)
      return false;
    try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
      raf.seek(128);
      byte[] magic = new byte[4];
      raf.readFully(magic);
      for (int i = 0; i < 4; i++)
        if (magic[i] != DICOM_MAGIC[i])
          return false;
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * Collects all regular files under {@code input} (recursively if directory).
   */
  public static List<File> collectFiles(File input) {
    if (input.isFile())
      return Collections.singletonList(input);
    List<File> files = new ArrayList<>();
    try (Stream<Path> s = Files.walk(input.toPath())) {
      s.filter(Files::isRegularFile).map(Path::toFile).sorted().forEach(files::add);
    } catch (IOException e) {
      LOGGER.warn("Cannot walk {}", input, e);
    }
    return files;
  }

  /**
   * Extracts Secondary Capture images from {@code files} into {@code outputDir}
   * as PNGs.
   *
   * @return sorted list of generated PNG files
   */
  public static List<File> extractSecondaryCaptures(List<File> files, File outputDir)
      throws IOException {

    outputDir.mkdirs();
    List<File> pngs = new ArrayList<>();
    int counter = 0;
    lastTotal = files.size();
    lastDicom = 0;
    lastSc = 0;
    lastPixOk = 0;

    // ── Phase 1: header scan (pure Java–no dcm4che3) ─────────────────────────
    List<File> scCandidates = new ArrayList<>();

    for (File file : files) {
      if (!isDicom(file)) {
        // Try as plain image (PNG/JPEG/etc.)
        try {
          BufferedImage img = ImageIO.read(file);
          if (img != null) {
            File dest = new File(outputDir, String.format("sc_%04d.png", counter++));
            ImageIO.write(ensureRgb(img), "png", dest);
            pngs.add(dest);
          }
        } catch (Exception ignore) {
        }
        continue;
      }

      lastDicom++;
      String[] tags = readModalityAndSOP(file);
      String modality = tags[0];
      String sopUID = tags[1];
      String fmiSop = tags[2]; // MediaStorageSOPClassUID from FMI

      // Primary check: SOPClassUID in dataset OR MediaStorageSOPClassUID in FMI
      // (some DICOMs have Modality='CT' even for Secondary Captures)
      boolean isSC = "SC".equalsIgnoreCase(modality)
          || isScSopUid(sopUID)
          || isScSopUid(fmiSop);

      LOGGER.debug("{} → Modality='{}' SOP='{}' SC={}", file.getName(), modality, sopUID, isSC);
      if (isSC) {
        scCandidates.add(file);
        lastSc++;
      }
    }

    LOGGER.info("Phase1: total={} dicom={} sc={}", lastTotal, lastDicom, lastSc);
    if (scCandidates.isEmpty()) {
      pngs.sort(null);
      return pngs;
    }

    // ── Phase 2: Python extraction (pydicom + Pillow) ─────────────────────────
    // All DICOM pixel extraction goes through Python. Pillow's libjpeg backend
    // reads JFIF APP0 and Adobe APP14 colour-space markers and performs the
    // YCbCr→RGB conversion during JPEG decode — the only reliable way to get
    // correct colours for JPEG-compressed SC files without any statistical
    // heuristics that can misfire.
    LOGGER.info("Extracting SC pixels via Python for {} file(s)…", scCandidates.size());
    List<File> pythonResult = extractWithPython(scCandidates, outputDir, counter);
    counter += pythonResult.size();
    pngs.addAll(pythonResult);
    lastPixOk += pythonResult.size();

    LOGGER.info("Phase2: {} image(s) extracted via Python.", lastPixOk);
    pngs.sort(null);
    return pngs;
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Pure-Java DICOM tag reader — reads Modality (0008,0060) and SOPClassUID
  // (0008,0016)
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * Returns {@code String[]{modality, sopClassUID, mediaStorageSopClassUID}}.
   * Reads the FMI (0002,0002) for MediaStorageSOPClassUID AND the dataset
   * tags (0008,0016) SOPClassUID and (0008,0060) Modality.
   */
  static String[] readModalityAndSOP(File file) {
    String modality = "";
    String sopUid = "";
    String fmiSop = ""; // from FMI (0002,0002) — most reliable

    try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
      long fileLen = raf.length();
      raf.seek(132);

      // --- Read FMI ---
      boolean explicitVR = true;
      long datasetStart = 132;

      int grp = readU16LE(raf);
      int el = readU16LE(raf);
      if (grp == 0x0002 && el == 0x0000) {
        raf.skipBytes(4); // VR "UL" + 2-byte length
        long fmiLen = readU32LE(raf);
        long fmiEnd = raf.getFilePointer() + fmiLen;

        // Scan remaining FMI for 0002,0002 and 0002,0010
        while (raf.getFilePointer() < fmiEnd - 4 && raf.getFilePointer() < fileLen - 8) {
          grp = readU16LE(raf);
          el = readU16LE(raf);
          String vr = readAscii(raf, 2);
          long tagLen;
          if (isExtendedVR(vr)) {
            raf.skipBytes(2);
            tagLen = readU32LE(raf);
          } else {
            tagLen = readU16LE(raf);
          }
          if (tagLen > 1024 || tagLen < 0) {
            break;
          }
          if (grp == 0x0002 && el == 0x0002) {
            byte[] ts = new byte[(int) tagLen];
            raf.readFully(ts);
            fmiSop = new String(ts).trim().replace("\0", "");
          } else if (grp == 0x0002 && el == 0x0010) {
            byte[] ts = new byte[(int) tagLen];
            raf.readFully(ts);
            String tsStr = new String(ts).trim().replace("\0", "");
            if ("1.2.840.10008.1.2".equals(tsStr))
              explicitVR = false;
          } else {
            raf.skipBytes((int) tagLen);
          }
        }
        datasetStart = fmiEnd;
      } else {
        // Fallback: scan past group 0002
        raf.seek(132);
        while (raf.getFilePointer() < fileLen - 8) {
          long pos = raf.getFilePointer();
          grp = readU16LE(raf);
          if (grp != 0x0002) {
            datasetStart = pos;
            break;
          }
          el = readU16LE(raf);
          String vr = readAscii(raf, 2);
          long tagLen = isExtendedVR(vr)
              ? (raf.skipBytes(2) >= 0 ? readU32LE(raf) : 0)
              : readU16LE(raf);
          if (tagLen > 0 && tagLen < 100000)
            raf.skipBytes((int) tagLen);
          else
            break;
        }
      }

      raf.seek(datasetStart);

      // --- Scan dataset for SOPClassUID (0008,0016) and Modality (0008,0060) ---
      for (int limit = 0; limit < 500 && raf.getFilePointer() < fileLen - 8; limit++) {
        grp = readU16LE(raf);
        el = readU16LE(raf);
        int tag32 = (grp << 16) | el;

        // Stop after group 0x0020 (Patient Study) — Modality/SOP are in group 0x0008
        if (grp > 0x0020)
          break;
        // Skip FFFE delimiter tags
        if (grp == 0xFFFE) {
          raf.skipBytes(4);
          continue;
        }

        long tagLen;
        if (explicitVR) {
          String vr = readAscii(raf, 2);
          if (isExtendedVR(vr)) {
            raf.skipBytes(2);
            tagLen = readU32LE(raf);
          } else {
            tagLen = readU16LE(raf);
          }
        } else {
          tagLen = readU32LE(raf);
        }

        // Undefined length (0xFFFFFFFF) = SQ with items — skip safely by stopping
        if (tagLen == 0xFFFFFFFFL) {
          // Can't skip without parsing item delimiters — stop here
          // SOPClassUID and Modality should appear BEFORE any undefined-length SQ
          break;
        }
        if (tagLen > 4096) {
          raf.skipBytes(64);
          continue;
        } // safety skip
        if (tagLen < 0) {
          break;
        }

        if (tag32 == 0x00080016 || tag32 == 0x00080060) {
          byte[] val = new byte[(int) tagLen];
          raf.readFully(val);
          String strVal = new String(val).trim().replace("\0", "");
          if (tag32 == 0x00080016)
            sopUid = strVal;
          else
            modality = strVal;
        } else {
          raf.skipBytes((int) tagLen);
        }

        if (!sopUid.isEmpty() && !modality.isEmpty())
          break;
      }

    } catch (Exception e) {
      LOGGER.debug("readModalityAndSOP({}): {}", file.getName(), e.getMessage());
    }

    return new String[] { modality.trim(), sopUid.trim(), fmiSop.trim() };
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Image extraction — Python (pydicom + Pillow) for all SC transfer syntaxes
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * Calls the bundled {@code dicom_extract_pixels.py} via Python to extract
   * pixels from {@code files} that Java could not decode (JPEG Lossless, etc.).
   *
   * @param files     list of SC DICOM files Java failed to decode
   * @param outputDir destination directory for PNGs
   * @param startIdx  index to start naming files from (sc_XXXX.png)
   * @return list of successfully extracted PNG files
   */
  static List<File> extractWithPython(List<File> files, File outputDir, int startIdx) {
    List<File> result = new ArrayList<>();

    String python = findPython();
    if (python == null) {
      LOGGER.warn("python3 not found — cannot extract JPEG Lossless pixels.");
      return result;
    }

    File scriptFile = extractBundledScript("dicom_extract_pixels.py");
    if (scriptFile == null) {
      LOGGER.warn("Could not extract bundled dicom_extract_pixels.py");
      return result;
    }

    File tmpOut = new File(outputDir, "python_tmp");
    tmpOut.mkdirs();

    List<String> cmd = new ArrayList<>();
    cmd.add(python);
    cmd.add(scriptFile.getAbsolutePath());
    cmd.add(tmpOut.getAbsolutePath());
    for (File f : files)
      cmd.add(f.getAbsolutePath());

    LOGGER.info("Calling Python for {} SC file(s): {}", files.size(), scriptFile.getName());

    try {
      ProcessBuilder pb = new ProcessBuilder(cmd);
      // ── CRITICAL: merge stderr into stdout to prevent pipe-buffer deadlock ──
      pb.redirectErrorStream(true);
      Process proc = pb.start();

      // Read merged stdout+stderr; "OK:" and "FAIL:" lines are from the script;
      // everything else is Python logging (warnings, etc.) → log at DEBUG
      try (java.io.BufferedReader br = new java.io.BufferedReader(
          new java.io.InputStreamReader(proc.getInputStream()))) {
        int idx = startIdx;
        String line;
        while ((line = br.readLine()) != null) {
          if (line.startsWith("OK:")) {
            File src = new File(line.substring(3).trim());
            if (src.exists()) {
              File dest = new File(outputDir, String.format("sc_%04d.png", idx++));
              if (src.renameTo(dest) || copyFile(src, dest))
                result.add(dest);
            }
          } else if (line.startsWith("FAIL:")) {
            LOGGER.warn("Python extraction: {}", line.substring(5));
          } else {
            LOGGER.debug("[py] {}", line); // warnings, imports, etc.
          }
        }
      }

      boolean finished = proc.waitFor(180, java.util.concurrent.TimeUnit.SECONDS);
      if (!finished) {
        LOGGER.warn("Python extraction timed out after 180 s — destroying process.");
        proc.destroyForcibly();
      } else {
        LOGGER.info("Python extraction finished (exit={}), {} images.", proc.exitValue(), result.size());
      }
      deleteDirQuietly(tmpOut);

    } catch (Exception e) {
      LOGGER.error("Python pixel extraction failed", e);
    }

    return result;
  }

  /**
   * Extracts a resource from the plugin JAR to a temp file (same pattern as
   * PivotDetector).
   */
  private static File extractBundledScript(String resourceName) {
    // Use leading "/" as PivotDetector does — works in OSGi
    try (java.io.InputStream in = DicomExtractor.class.getResourceAsStream("/" + resourceName)) {
      if (in != null) {
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("sc_extract_", ".py");
        java.nio.file.Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        tmp.toFile().deleteOnExit();
        return tmp.toFile();
      }
    } catch (Exception e) {
      LOGGER.debug("getResourceAsStream failed for {}: {}", resourceName, e.getMessage());
    }

    // Fallback: look next to the JAR file
    try {
      File jarDir = new File(DicomExtractor.class.getProtectionDomain()
          .getCodeSource().getLocation().toURI()).getParentFile();
      File next = new File(jarDir, resourceName);
      if (next.exists())
        return next;
    } catch (Exception ignored) {
    }

    return null;
  }

  /**
   * Finds a usable Python 3 interpreter, preferring the bundled venv shipped
   * alongside the plugin JAR so the software works without any pre-installed
   * Python on the target machine.
   *
   * <p>Search order:
   * <ol>
   *   <li>Bundled venv: {@code <appDir>/python-env/} (co-installed by distribute.sh)</li>
   *   <li>User-level venv: {@code ~/.weasis/labrom-env/} (created by setup-python.sh)</li>
   *   <li>System Python 3 / Python on PATH</li>
   *   <li>Common absolute paths (Homebrew, pyenv, system)</li>
   * </ol>
   */
  static String findPython() {
    // 1. Bundled venv co-located with the plugin JAR
    //    JAR lives in <appDir>/bundle/, venv in <appDir>/python-env/
    try {
      java.io.File jar = new java.io.File(
          DicomExtractor.class.getProtectionDomain()
              .getCodeSource().getLocation().toURI());
      java.io.File appDir = jar.getParentFile().getParentFile(); // up from bundle/
      for (String rel : new String[]{
          "python-env/bin/python3",
          "python-env/bin/python",
          "python-env/Scripts/python.exe"   // Windows layout
      }) {
        java.io.File candidate = new java.io.File(appDir, rel);
        if (candidate.canExecute()) {
          LOGGER.info("Using bundled Python venv: {}", candidate);
          return candidate.getAbsolutePath();
        }
      }
    } catch (Exception ignore) {}

    // 2. User-level venv created by setup-python.sh / setup-python.bat
    String home = System.getProperty("user.home");
    for (String rel : new String[]{
        ".weasis/labrom-env/bin/python3",
        ".weasis/labrom-env/bin/python",
        ".weasis/labrom-env/Scripts/python.exe",   // Windows (setup-python.bat)
        "AppData/Local/Programs/Python/Python312/python.exe", // Windows system fallback
        "AppData/Local/Programs/Python/Python311/python.exe"
    }) {
      java.io.File candidate = new java.io.File(home, rel);
      if (candidate.canExecute()) {
        LOGGER.info("Using user-level Python: {}", candidate);
        return candidate.getAbsolutePath();
      }
    }

    // 3. System Python on PATH
    for (String name : new String[]{"python3", "python"}) {
      try {
        Process p = new ProcessBuilder(name, "--version")
            .redirectErrorStream(true).start();
        if (p.waitFor() == 0) return name;
      } catch (Exception ignored) {}
    }

    // 4. Common absolute paths (macOS Homebrew, pyenv, system)
    for (String path : new String[]{
        "/usr/bin/python3",
        "/usr/local/bin/python3",
        "/opt/homebrew/bin/python3",
        home + "/.pyenv/shims/python3"
    }) {
      if (new java.io.File(path).canExecute()) return path;
    }

    return null;
  }

  private static boolean copyFile(File src, File dest) {
    try {
      java.nio.file.Files.copy(src.toPath(), dest.toPath(),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private static void deleteDirQuietly(File dir) {
    if (dir == null || !dir.exists())
      return;
    File[] children = dir.listFiles();
    if (children != null)
      for (File c : children)
        deleteDirQuietly(c);
    dir.delete();
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Binary helpers
  // ───────────────────────────────────────────────────────────────────────────

  private static int readU16LE(RandomAccessFile raf) throws IOException {
    int b0 = raf.read();
    int b1 = raf.read();
    if (b0 < 0 || b1 < 0)
      throw new IOException("EOF");
    return (b0 & 0xFF) | ((b1 & 0xFF) << 8);
  }

  private static long readU32LE(RandomAccessFile raf) throws IOException {
    long b0 = raf.read();
    long b1 = raf.read();
    long b2 = raf.read();
    long b3 = raf.read();
    if (b3 < 0)
      throw new IOException("EOF");
    return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
  }

  private static String readAscii(RandomAccessFile raf, int n) throws IOException {
    byte[] buf = new byte[n];
    raf.readFully(buf);
    return new String(buf);
  }

  private static boolean isExtendedVR(String vr) {
    return "OB".equals(vr) || "OW".equals(vr) || "OF".equals(vr) || "SQ".equals(vr)
        || "UC".equals(vr) || "UN".equals(vr) || "UR".equals(vr) || "UT".equals(vr)
        || "OD".equals(vr) || "OL".equals(vr) || "OV".equals(vr);
  }

  static BufferedImage ensureRgb(BufferedImage img) {
    if (img.getType() == BufferedImage.TYPE_INT_RGB)
      return img;
    BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(),
        BufferedImage.TYPE_INT_RGB);
    rgb.createGraphics().drawImage(img, 0, 0, null);
    return rgb;
  }

}
