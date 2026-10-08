package com.parametrix;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class CadRunnerTest {
  @TempDir Path temp;

  static byte[] triangle() {
    ByteBuffer bytes = ByteBuffer.allocate(134).order(ByteOrder.LITTLE_ENDIAN);
    bytes.position(80);
    bytes.putInt(1);
    for (float value : new float[] {0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0}) bytes.putFloat(value);
    bytes.putShort((short) 0);
    return bytes.array();
  }

  Path fixture(String body) throws Exception {
    Path file = temp.resolve("runner-" + System.nanoTime());
    Files.writeString(file, "#!/bin/sh\n" + body);
    file.toFile().setExecutable(true);
    return file;
  }

  @Test
  void validatesExternalOperationsAndComments() {
    assertDoesNotThrow(() -> CadRunner.validate("// import(\"x\");\nx=\"/*\"; cube(1);"));
    for (String source :
        new String[] {
          "include <x>",
          "use/*comment*/<x>",
          "import (\"x\");",
          "surface(file=\"x\");",
          "x=\"//\"; import(\"x\");",
          ""
        }) assertThrows(IllegalArgumentException.class, () -> CadRunner.validate(source));
  }

  @Test
  void rejectsEmptyAndMalformedMesh() throws Exception {
    Path file = temp.resolve("test.stl");
    Files.write(file, new byte[84]);
    assertFalse(CadRunner.validStl(file));
    Files.write(file, triangle());
    assertTrue(CadRunner.validStl(file));
    byte[] bad = triangle();
    ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putFloat(96, Float.NaN);
    Files.write(file, bad);
    assertFalse(CadRunner.validStl(file));
    Files.write(file, new byte[134]);
    assertFalse(CadRunner.validStl(file));
  }

  @Test
  void capturesOutputAndExportsMesh() throws Exception {
    Path mesh = temp.resolve("fixture.stl");
    Files.write(mesh, triangle());
    Path script = fixture("echo stdout; echo stderr >&2; cp '" + mesh + "' \"$4\"\n");
    var result =
        new CadRunner(script.toString(), 2)
            .render("cube(1);", temp.resolve("success"), () -> false, line -> {});
    assertTrue(result.success(), result.diagnostics());
    assertTrue(result.diagnostics().contains("stdout"));
    assertTrue(result.diagnostics().contains("stderr"));
  }

  @Test
  void capsOutputWithoutBlockingTheProcess() throws Exception {
    Path mesh = temp.resolve("bounded.stl");
    Files.write(mesh, triangle());
    Path script = fixture("head -c 100000 /dev/zero | tr '\\000' 'x'; cp '" + mesh + "' \"$4\"\n");
    java.util.concurrent.atomic.AtomicInteger bytes =
        new java.util.concurrent.atomic.AtomicInteger();
    var result =
        new CadRunner(script.toString(), 3)
            .render(
                "cube(1);",
                temp.resolve("bounded"),
                () -> false,
                line -> bytes.addAndGet(line.length()));
    assertTrue(result.success(), result.diagnostics());
    assertEquals(32768, bytes.get());
    assertEquals(32768, result.diagnostics().length());
  }

  @Test
  void detectsCompilerErrorsAndEmptyOutput() throws Exception {
    var script = fixture("echo 'ERROR: invalid' >&2; exit 0\n");
    var result =
        new CadRunner(script.toString(), 2)
            .render("cube(1);", temp.resolve("bad"), () -> false, line -> {});
    assertFalse(result.success());
    assertTrue(result.diagnostics().contains("ERROR:"));
  }

  @Test
  void killsProcessAndChildrenOnTimeout() throws Exception {
    Path pid = temp.resolve("child.pid");
    Path script = fixture("sleep 60 &\necho $! > '" + pid + "'\nwait\n");
    assertTimeoutPreemptively(
        Duration.ofSeconds(7),
        () -> {
          var result =
              new CadRunner(script.toString(), 2)
                  .render("cube(1);", temp.resolve("timeout"), () -> false, line -> {});
          assertFalse(result.success());
          assertTrue(result.diagnostics().contains("timed out"));
        });
    long child = Long.parseLong(Files.readString(pid).trim());
    assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
  }

  @Test
  void cancellationStopsRunningProcess() throws Exception {
    Path script = fixture("sleep 60\n");
    long started = System.nanoTime();
    var result =
        new CadRunner(script.toString(), 30)
            .render(
                "cube(1);",
                temp.resolve("cancel"),
                () -> System.nanoTime() - started > 200_000_000,
                line -> {});
    assertFalse(result.success());
    assertTrue(result.diagnostics().contains("cancelled"));
  }

  @Test
  void openScadCubeBounds() throws Exception {
    String executable = System.getenv("OPENSCAD_PATH");
    Assumptions.assumeTrue(
        executable != null && Files.isExecutable(Path.of(executable)),
        "Set OPENSCAD_PATH for integration test");
    var result =
        new CadRunner(executable, 30)
            .render("cube([10,20,30]);", temp.resolve("real"), () -> false, line -> {});
    assertTrue(result.success(), result.diagnostics());
    ByteBuffer mesh =
        ByteBuffer.wrap(Files.readAllBytes(result.mesh())).order(ByteOrder.LITTLE_ENDIAN);
    int faces = mesh.getInt(80);
    float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE},
        max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
    for (int i = 0; i < faces; i++)
      for (int v = 0; v < 3; v++)
        for (int axis = 0; axis < 3; axis++) {
          float value = mesh.getFloat(84 + i * 50 + 12 + v * 12 + axis * 4);
          min[axis] = Math.min(min[axis], value);
          max[axis] = Math.max(max[axis], value);
        }
    assertArrayEquals(new float[] {0, 0, 0}, min);
    assertArrayEquals(new float[] {10, 20, 30}, max);
  }
}
