package com.google.copybara.util;

import static com.google.common.truth.Truth.assertThat;
import static com.google.copybara.testing.FileSubjects.assertThatPath;

import com.google.common.collect.ImmutableSet;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;

@RunWith(JUnit4.class)
public class FileUtilTest {

  @Test
  public void testCopy() throws Exception {
    Path one = Files.createTempDirectory("one");
    Path two = Files.createTempDirectory("two");
    Path absolute = touch(Files.createTempDirectory("absolute").resolve("absolute"));
    Files.createDirectories(two.getParent());

    Files.setPosixFilePermissions(touch(one.resolve("foo")),
        ImmutableSet.of(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_READ));
    touch(one.resolve("some/folder/bar"));

    Files.createSymbolicLink(one.resolve("some/folder/baz"),
        one.getFileSystem().getPath("../../foo"));

    Path absoluteTarget = one.resolve("some/folder").relativize(absolute);
    Files.createSymbolicLink(one.resolve("some/folder/absolute"), absoluteTarget);

    FileUtil.copyFilesRecursively(one, two);

    assertThatPath(two)
        .containsFile("foo", "abc")
        .containsFile("some/folder/bar", "abc")
        .containsFile("some/folder/absolute", "abc")
        .containsFile("some/folder/baz", "abc")
        .containsNoMoreFiles();

    assertThat(Files.isExecutable(two.resolve("foo"))).isTrue();
    assertThat(Files.isExecutable(two.resolve("some/folder/bar"))).isFalse();
    assertThat(Files.readSymbolicLink(two.resolve("some/folder/baz")).toString())
        .isEqualTo(two.getFileSystem().getPath("../../foo").toString());
    assertThat(Files.readSymbolicLink(two.resolve("some/folder/absolute")).toString())
        .isEqualTo(absoluteTarget.toString());
  }

  private Path touch(Path path) throws IOException {
    Files.createDirectories(path.getParent());
    Files.write(path, "abc".getBytes());
    return path;
  }
}