/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.carbondata.core.util;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

import org.apache.carbondata.common.logging.LogServiceFactory;
import org.apache.carbondata.core.constants.CarbonCommonConstants;
import org.apache.carbondata.core.datastore.filesystem.CarbonFile;
import org.apache.carbondata.core.datastore.impl.FileFactory;
import org.apache.carbondata.core.mutate.CarbonUpdateUtil;
import org.apache.carbondata.core.util.path.CarbonTablePath;

import org.apache.hadoop.io.IOUtils;
import org.apache.log4j.Logger;

/**
 * Mantains the trash folder in carbondata. This class has methods to copy data to the trash and
 * remove data from the trash.
 */
public final class TrashUtil {

  private static final Logger LOGGER =
      LogServiceFactory.getLogService(TrashUtil.class.getName());

  /**
   * Base method to copy the data to the trash folder.
   *
   * @param sourcePath      the path from which to copy the file
   * @param destinationPath the path where the file will be copied
   * @return
   */
  private static void copyToTrashFolder(String sourcePath, String destinationPath)
    throws IOException {
    DataOutputStream dataOutputStream = null;
    DataInputStream dataInputStream = null;
    try {
      dataOutputStream = FileFactory.getDataOutputStream(destinationPath);
      dataInputStream = FileFactory.getDataInputStream(sourcePath);
      IOUtils.copyBytes(dataInputStream, dataOutputStream, CarbonCommonConstants.BYTEBUFFER_SIZE);
    } catch (IOException exception) {
      LOGGER.error("Unable to copy " + sourcePath + " to the trash folder", exception);
      throw exception;
    } finally {
      CarbonUtil.closeStreams(dataInputStream, dataOutputStream);
    }
  }

  /**
   * The below method copies the complete a file to the trash folder.
   *
   * @param filePathToCopy           the files which are to be moved to the trash folder
   * @param trashFolderWithTimestamp timestamp, partition folder(if any) and segment number
   * @return
   */
  public static void copyFileToTrashFolder(String filePathToCopy,
      String trashFolderWithTimestamp) throws IOException {
    CarbonFile carbonFileToCopy = FileFactory.getCarbonFile(filePathToCopy);
    String destinationPath = trashFolderWithTimestamp + CarbonCommonConstants
        .FILE_SEPARATOR + carbonFileToCopy.getName();
    try {
      if (!FileFactory.isFileExist(destinationPath)) {
        copyToTrashFolder(filePathToCopy, destinationPath);
      }
    } catch (IOException e) {
      // in case there is any issue while copying the file to the trash folder, we need to delete
      // the complete segment folder from the trash folder. The trashFolderWithTimestamp contains
      // the segment folder too. Delete the folder as it is.
      FileFactory.deleteFile(trashFolderWithTimestamp);
      LOGGER.error("Error while checking trash folder: " + destinationPath + " or copying" +
          " file: " + filePathToCopy + " to the trash folder at path", e);
      throw e;
    }
  }

  /**
   * The below method copies the complete segment folder to the trash folder. Here, the data files
   * in segment are listed and copied one by one to the trash folder.
   *
   * @param segmentPath              the folder which are to be moved to the trash folder
   * @param trashFolderWithTimestamp trashfolderpath with complete timestamp and segment number
   * @return
   */
  public static void copySegmentToTrash(CarbonFile segmentPath,
      String trashFolderWithTimestamp) throws IOException {
    try {
      if (segmentPath.isFileExist()) {
        if (!FileFactory.isFileExist(trashFolderWithTimestamp)) {
          FileFactory.mkdirs(trashFolderWithTimestamp);
        }
        CarbonFile[] dataFiles = segmentPath.listFiles();
        for (CarbonFile carbonFile : dataFiles) {
          copyFileToTrashFolder(carbonFile.getAbsolutePath(), trashFolderWithTimestamp);
        }
        LOGGER.info("Segment: " + segmentPath.getAbsolutePath() + " has been copied to" +
            " the trash folder successfully. Total files copied: " + dataFiles.length);
      } else {
        LOGGER.info("Segment: " + segmentPath.getAbsolutePath() + " does not exist");
      }
    } catch (IOException e) {
      LOGGER.error("Error while copying the segment: " + segmentPath.getName() + " to the trash" +
          " Folder: " + trashFolderWithTimestamp, e);
      throw e;
    }
  }

  /**
   * The below method copies multiple files belonging to 1 segment to the trash folder.
   *
   * @param filesToCopy              absolute paths of the files to copy to the trash folder
   * @param trashFolderWithTimestamp trashfolderpath with complete timestamp and segment number
   * @param segmentNumber            segment number of the files which are being copied to trash
   * @return
   */
  public static void copyFilesToTrash(List<String> filesToCopy,
      String trashFolderWithTimestamp, String segmentNumber) throws IOException {
    try {
      if (!FileFactory.isFileExist(trashFolderWithTimestamp)) {
        FileFactory.mkdirs(trashFolderWithTimestamp);
      }
      for (String fileToCopy : filesToCopy) {
        // check if file exists before copying
        if (FileFactory.isFileExist(fileToCopy)) {
          copyFileToTrashFolder(fileToCopy, trashFolderWithTimestamp);
        }
      }
      LOGGER.info("Segment: " + segmentNumber + " has been copied to" +
          " the trash folder successfully");
    } catch (IOException e) {
      LOGGER.error("Error while copying files of segment: " + segmentNumber + " to the trash" +
          " folder", e);
      throw e;
    }
  }

  /**
   * The below method deletes timestamp subdirectories in the trash folder which have expired as
   * per the user defined retention time
   */
  public static void deleteExpiredDataFromTrash(String tablePath) {
    String trashPath = CarbonTablePath.getTrashFolderPath(tablePath);
    // Deleting the timestamp based subdirectories in the trashfolder by the given timestamp.
    try {
      if (FileFactory.isFileExist(trashPath)) {
        List<CarbonFile> timestampFolderList = FileFactory.getFolderList(trashPath);
        for (CarbonFile timestampFolder : timestampFolderList) {
          // If the timeStamp at which the timeStamp subdirectory has expired as per the user
          // defined value, delete the complete timeStamp subdirectory
          if (isTrashRetentionTimeoutExceeded(Long.parseLong(timestampFolder.getName()))) {
            FileFactory.deleteAllCarbonFilesOfDir(timestampFolder);
            LOGGER.info("Timestamp subfolder from the Trash folder deleted: " + timestampFolder
                .getAbsolutePath());
          }
        }
      }
    } catch (IOException e) {
      LOGGER.error("Error during deleting expired timestamp folder from the trash folder", e);
    }
  }

  /**
   * The below method deletes all the files and folders in the trash folder of a carbon table.
   */
  public static void emptyTrash(String tablePath) {
    String trashPath = CarbonTablePath.getTrashFolderPath(tablePath);
    // if the trash folder exists delete the contents of the trash folder
    try {
      if (FileFactory.isFileExist(trashPath)) {
        List<CarbonFile> carbonFileList = FileFactory.getFolderList(trashPath);
        for (CarbonFile carbonFile : carbonFileList) {
          FileFactory.deleteAllCarbonFilesOfDir(carbonFile);
        }
      }
    } catch (IOException e) {
      LOGGER.error("Error while emptying the trash folder", e);
    }
  }

  /**
   * This will tell whether the trash retention time has expired or not
   *
   * @param fileTimestamp
   * @return
   */
  public static boolean isTrashRetentionTimeoutExceeded(long fileTimestamp) {
    // record current time.
    long currentTime = CarbonUpdateUtil.readCurrentTime();
    long retentionMilliSeconds = CarbonProperties.getInstance().getTrashFolderRetentionTime();
    long difference = currentTime - fileTimestamp;
    return difference > retentionMilliSeconds;
  }

  /**
   * This will give the complete path of the trash folder with the timestamp and the segment number
   *
   * @param tablePath          absolute table path
   * @param timeStampSubFolder the timestamp for the clean files operation
   * @param segmentNumber      the segment number for which files are moved to the trash folder
   * @return
   */
  public static String getCompleteTrashFolderPath(String tablePath, long timeStampSubFolder,
      String segmentNumber) {
    return CarbonTablePath.getTrashFolderPath(tablePath) + CarbonCommonConstants.FILE_SEPARATOR +
      timeStampSubFolder + CarbonCommonConstants.FILE_SEPARATOR + CarbonTablePath
      .SEGMENT_PREFIX + segmentNumber;
  }
}
