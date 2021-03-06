/*
 * Copyright 2000-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.util.download.impl;

import com.intellij.ide.IdeBundle;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.application.Result;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.*;
import com.intellij.util.download.DownloadableFileDescription;
import com.intellij.util.download.FileDownloader;
import com.intellij.util.io.UrlConnectionUtil;
import com.intellij.util.net.IOExceptionDialog;
import com.intellij.util.net.NetUtils;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * @author nik
 */
public class FileDownloaderImpl implements FileDownloader {
  private static final int CONNECTION_TIMEOUT = 60*1000;
  private static final int READ_TIMEOUT = 60*1000;
  @NonNls private static final String LIB_SCHEMA = "lib://";

  private final List<? extends DownloadableFileDescription> myFileDescriptions;
  private JComponent myParentComponent;
  private @Nullable Project myProject;
  private String myDirectoryForDownloadedFilesPath;
  private final String myDialogTitle;

  public FileDownloaderImpl(@NotNull List<? extends DownloadableFileDescription> fileDescriptions,
                            final @Nullable Project project,
                            @Nullable JComponent parentComponent,
                            @NotNull String presentableDownloadName) {
    myProject = project;
    myFileDescriptions = fileDescriptions;
    myParentComponent = parentComponent;
    myDialogTitle = IdeBundle.message("progress.download.0.title", StringUtil.capitalize(presentableDownloadName));
  }

  @Nullable
  @Override
  public List<VirtualFile> downloadFilesWithProgress(@Nullable String targetDirectoryPath,
                                                     @Nullable Project project,
                                                     @Nullable JComponent parentComponent) {
    final List<Pair<VirtualFile, DownloadableFileDescription>> pairs = downloadWithProgress(targetDirectoryPath, project, parentComponent);
    if (pairs == null) return null;

    List<VirtualFile> files = new ArrayList<VirtualFile>();
    for (Pair<VirtualFile, DownloadableFileDescription> pair : pairs) {
      files.add(pair.getFirst());
    }
    return files;
  }

  @Nullable
  @Override
  public List<Pair<VirtualFile, DownloadableFileDescription>> downloadWithProgress(@Nullable String targetDirectoryPath,
                                                                                   @Nullable Project project,
                                                                                   @Nullable JComponent parentComponent) {
    File dir;
    if (targetDirectoryPath != null) {
      dir = new File(targetDirectoryPath);
    }
    else {
      VirtualFile virtualDir = chooseDirectoryForFiles(project, parentComponent);
      if (virtualDir != null) {
        dir = VfsUtilCore.virtualToIoFile(virtualDir);
      }
      else {
        return null;
      }
    }

    return downloadWithProcess(dir, project, parentComponent);
  }

  @Nullable
  private List<Pair<VirtualFile,DownloadableFileDescription>> downloadWithProcess(final File targetDir,
                                                                                  Project project,
                                                                                  JComponent parentComponent) {
    final Ref<List<Pair<File, DownloadableFileDescription>>> localFiles = Ref.create(null);
    final Ref<IOException> exceptionRef = Ref.create(null);

    boolean completed = ProgressManager.getInstance().runProcessWithProgressSynchronously(new Runnable() {
      @Override
      public void run() {
        try {
          localFiles.set(download(targetDir));
        }
        catch (IOException e) {
          exceptionRef.set(e);
        }
      }
    }, myDialogTitle, true, project, parentComponent);
    if (!completed) {
      return null;
    }

    Exception exception = exceptionRef.get();
    if (exception != null) {
      final boolean tryAgain = IOExceptionDialog.showErrorDialog(myDialogTitle, exception.getMessage());
      if (tryAgain) {
        return downloadWithProcess(targetDir, project, parentComponent);
      }
      return null;
    }

    return findVirtualFiles(localFiles.get());
  }

  @NotNull
  @Override
  public List<Pair<File, DownloadableFileDescription>> download(@NotNull File targetDir) throws IOException {
    final List<Pair<File, DownloadableFileDescription>> downloadedFiles = new ArrayList<Pair<File, DownloadableFileDescription>>();
    final List<Pair<File, DownloadableFileDescription>> existingFiles = new ArrayList<Pair<File, DownloadableFileDescription>>();
    ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
    if (indicator == null) {
      indicator = new EmptyProgressIndicator();
    }
    try {
      for (int i = 0; i < myFileDescriptions.size(); i++) {
        DownloadableFileDescription description = myFileDescriptions.get(i);
        indicator.checkCanceled();
        indicator.setText(IdeBundle.message("progress.downloading.0.of.1.file.text", i + 1, myFileDescriptions.size()));

        final File existing = new File(targetDir, description.getDefaultFileName());
        final String url = description.getDownloadUrl();
        if (url.startsWith(LIB_SCHEMA)) {
          indicator.setText2(IdeBundle.message("progress.locate.file.text", description.getPresentableFileName()));
          final String path = FileUtil.toSystemDependentName(StringUtil.trimStart(url, LIB_SCHEMA));
          final File file = PathManager.findFileInLibDirectory(path);
          existingFiles.add(Pair.create(file, description));
        }
        else if (url.startsWith(LocalFileSystem.PROTOCOL_PREFIX)) {
          String path = FileUtil.toSystemDependentName(StringUtil.trimStart(url, LocalFileSystem.PROTOCOL_PREFIX));
          File file = new File(path);
          if (file.exists()) {
            existingFiles.add(Pair.create(file, description));
          }
        }
        else {
          File downloaded;
          try {
            downloaded = downloadFile(description, existing, indicator);
          }
          catch (IOException e) {
            throw new IOException(IdeBundle.message("error.file.download.failed", description.getDownloadUrl(), e.getMessage()), e);
          }
          if (FileUtil.filesEqual(downloaded, existing)) {
            existingFiles.add(Pair.create(existing, description));
          }
          else {
            downloadedFiles.add(Pair.create(downloaded, description));
          }
        }
      }
      List<Pair<File, DownloadableFileDescription>> localFiles = new ArrayList<Pair<File, DownloadableFileDescription>>();
      localFiles.addAll(moveToDir(downloadedFiles, targetDir));
      localFiles.addAll(existingFiles);
      return localFiles;
    }
    catch (ProcessCanceledException e) {
      deleteFiles(downloadedFiles);
      throw e;
    }
    catch (IOException e) {
      deleteFiles(downloadedFiles);
      throw e;
    }
  }

  @Nullable
  private static VirtualFile chooseDirectoryForFiles(Project project, JComponent parentComponent) {
    final FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor();
    descriptor.setTitle(IdeBundle.message("dialog.directory.for.downloaded.files.title"));
    final VirtualFile baseDir = project != null ? project.getBaseDir() : null;
    return FileChooser.chooseFile(descriptor, parentComponent, project, baseDir);
  }

  private static List<Pair<File, DownloadableFileDescription>> moveToDir(List<Pair<File, DownloadableFileDescription>> downloadedFiles,
                                                                         final File targetDir) throws IOException {
    FileUtil.createDirectory(targetDir);
    List<Pair<File, DownloadableFileDescription>> result = new ArrayList<Pair<File, DownloadableFileDescription>>();
    for (Pair<File, DownloadableFileDescription> pair : downloadedFiles) {
      final DownloadableFileDescription description = pair.getSecond();
      final String fileName = description.generateFileName(new Condition<String>() {
        @Override
        public boolean value(String s) {
          return !new File(targetDir, s).exists();
        }
      });
      final File toFile = new File(targetDir, fileName);
      FileUtil.rename(pair.getFirst(), toFile);
      result.add(Pair.create(toFile, description));
    }
    return result;
  }

  @NotNull
  private static List<Pair<VirtualFile, DownloadableFileDescription>> findVirtualFiles(List<Pair<File, DownloadableFileDescription>> ioFiles) {
    List<Pair<VirtualFile,DownloadableFileDescription>> result = new ArrayList<Pair<VirtualFile, DownloadableFileDescription>>();
    for (final Pair<File, DownloadableFileDescription> pair : ioFiles) {
      final File ioFile = pair.getFirst();
      VirtualFile libraryRootFile = new WriteAction<VirtualFile>() {
        @Override
        protected void run(final Result<VirtualFile> result) {
          final String url = VfsUtil.getUrlForLibraryRoot(ioFile);
          LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile);
          result.setResult(VirtualFileManager.getInstance().refreshAndFindFileByUrl(url));
        }

      }.execute().getResultObject();
      if (libraryRootFile != null) {
        result.add(Pair.create(libraryRootFile, pair.getSecond()));
      }
    }
    return result;
  }

  private static void deleteFiles(final List<Pair<File, DownloadableFileDescription>> pairs) {
    for (Pair<File, DownloadableFileDescription> pair : pairs) {
      FileUtil.delete(pair.getFirst());
    }
  }

  @NotNull
  private static File downloadFile(final @NotNull DownloadableFileDescription fileDescription, final @NotNull File existingFile,
                                   final @NotNull ProgressIndicator indicator) throws IOException {
    final String presentableUrl = fileDescription.getPresentableDownloadUrl();
    indicator.setText2(IdeBundle.message("progress.connecting.to.download.file.text", presentableUrl));
    indicator.setIndeterminate(true);
    HttpURLConnection connection = (HttpURLConnection)new URL(fileDescription.getDownloadUrl()).openConnection();
    connection.setConnectTimeout(CONNECTION_TIMEOUT);
    connection.setReadTimeout(READ_TIMEOUT);

    InputStream input = null;
    BufferedOutputStream output = null;

    boolean deleteFile = true;
    File tempFile = null;
    try {
      final int responseCode = connection.getResponseCode();
      if (responseCode != HttpURLConnection.HTTP_OK) {
        throw new IOException(IdeBundle.message("error.connection.failed.with.http.code.N", responseCode));
      }

      final int size = connection.getContentLength();
      if (existingFile.exists() && size == existingFile.length()) {
        return existingFile;
      }

      tempFile = FileUtil.createTempFile("downloaded", "file");
      input = UrlConnectionUtil.getConnectionInputStreamWithException(connection, indicator);
      output = new BufferedOutputStream(new FileOutputStream(tempFile));
      indicator.setText2(IdeBundle.message("progress.download.file.text", fileDescription.getPresentableFileName(), presentableUrl));
      indicator.setIndeterminate(size == -1);

      NetUtils.copyStreamContent(indicator, input, output, size);

      deleteFile = false;
      return tempFile;
    }
    finally {
      if (input != null) {
        input.close();
      }
      if (output != null) {
        output.close();
      }
      if (deleteFile && tempFile != null) {
        FileUtil.delete(tempFile);
      }
      connection.disconnect();
    }
  }

  @NotNull
  @Override
  public FileDownloader toDirectory(@NotNull String directoryForDownloadedFilesPath) {
    myDirectoryForDownloadedFilesPath = directoryForDownloadedFilesPath;
    return this;
  }

  @Nullable
  @Override
  public VirtualFile[] download() {
    List<VirtualFile> files = downloadFilesWithProgress(myDirectoryForDownloadedFilesPath, myProject, myParentComponent);
    return files != null ? VfsUtilCore.toVirtualFileArray(files) : null;
  }

  @Nullable
  @Override
  public List<Pair<VirtualFile, DownloadableFileDescription>> downloadAndReturnWithDescriptions() {
    return downloadWithProgress(myDirectoryForDownloadedFilesPath, myProject, myParentComponent);
  }
}
