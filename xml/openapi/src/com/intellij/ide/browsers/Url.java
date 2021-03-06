package com.intellij.ide.browsers;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;

public interface Url {
  @NotNull
  String getPath();

  boolean isInLocalFileSystem();

  String toDecodedForm(boolean skipQueryAndFragment);

  @NotNull
  String toExternalForm();

  @NotNull
  String toExternalForm(boolean skipQueryAndFragment);

  @Nullable
  String getScheme();

  @Nullable
  String getAuthority();

  @Nullable
  String getParametersPart();

  boolean equalsIgnoreParameters(@Nullable Url url);

  @NotNull
  URI toJavaUriWithoutParameters();
}
