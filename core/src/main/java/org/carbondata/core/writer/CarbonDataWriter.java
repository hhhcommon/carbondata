/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.carbondata.core.writer;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.carbondata.common.logging.LogService;
import org.carbondata.common.logging.LogServiceFactory;
import org.carbondata.core.constants.CarbonCommonConstants;
import org.carbondata.core.datastorage.store.filesystem.CarbonFile;
import org.carbondata.core.datastorage.store.impl.FileFactory;
import org.carbondata.core.file.manager.composite.FileData;
import org.carbondata.core.file.manager.composite.IFileManagerComposite;
import org.carbondata.core.metadata.BlockletInfo;
import org.carbondata.core.util.CarbonCoreLogEvent;
import org.carbondata.core.util.CarbonProperties;
import org.carbondata.core.util.CarbonUtil;
import org.carbondata.core.writer.exception.CarbonDataWriterException;

public class CarbonDataWriter {
  /**
   * Attribute for Carbon LOGGER
   */
  private static final LogService LOGGER =
      LogServiceFactory.getLogService(CarbonDataWriter.class.getName());
  /**
   * table name
   */
  private String tableName;
  /**
   * data file size;
   */
  private long fileSizeInBytes;
  /**
   * measure count
   */
  private int measureCount;
  /**
   * this will be used for holding blocklet metadata
   */
  private List<BlockletInfo> blockletInfoList;
  /**
   * current size of file
   */
  private long currentFileSize;
  /**
   * leaf metadata size
   */
  private int leafMetaDataSize;
  /**
   * file count will be used to give sequence number to the data file
   */
  private int fileCount;
  /**
   * filename format
   */
  private String fileNameFormat;
  /**
   * file name
   */
  private String fileName;
  /**
   * File manager
   */
  private IFileManagerComposite fileManager;
  /**
   * Store Location
   */
  private String storeLocation;
  /**
   * fileExtension
   */
  private String fileExtension;
  /**
   * isNewFileCreationRequired
   */
  private boolean isNewFileCreationRequired;
  /**
   * isInProgressExtrequired
   */
  private boolean isInProgressExtrequired;
  /**
   * fileDataOutStream
   */
  private DataOutputStream fileDataOutStream;
  /**
   * metadataOffset for maintaining the offset of pagination file.
   */
  private int metadataOffset;

  /**
   * CarbonDataWriter constructor to initialize all the instance variables
   * required for wrting the data i to the file
   *
   * @param storeLocation current store location
   * @param measureCount  total number of measures
   * @param mdKeyLength   mdkey length
   * @param tableName     table name
   */
  public CarbonDataWriter(String storeLocation, int measureCount, int mdKeyLength, String tableName,
      String fileExtension, boolean isNewFileCreationRequired, boolean isInProgressExtrequired) {
    // measure count
    this.measureCount = measureCount;
    // table name
    this.tableName = tableName;

    this.storeLocation = storeLocation;
    this.fileExtension = fileExtension;
    // create the carbon file format
    this.fileNameFormat =
        storeLocation + File.separator + this.tableName + '_' + "{0}" + this.fileExtension;

    this.leafMetaDataSize = CarbonCommonConstants.INT_SIZE_IN_BYTE * (2 + measureCount)
        + CarbonCommonConstants.LONG_SIZE_IN_BYTE * (measureCount + 1) + (2 * mdKeyLength);
    this.blockletInfoList = new ArrayList<BlockletInfo>(CarbonCommonConstants.CONSTANT_SIZE_TEN);
    // get max file size;
    this.fileSizeInBytes = Long.parseLong(CarbonProperties.getInstance()
        .getProperty(CarbonCommonConstants.MAX_FILE_SIZE,
            CarbonCommonConstants.MAX_FILE_SIZE_DEFAULT_VAL))
        * CarbonCommonConstants.BYTE_TO_KB_CONVERSION_FACTOR
        * CarbonCommonConstants.BYTE_TO_KB_CONVERSION_FACTOR * 1L;
    this.isNewFileCreationRequired = isNewFileCreationRequired;
    this.isInProgressExtrequired = isInProgressExtrequired;
  }

  /**
   * This method will be used to initialize the channel
   *
   * @throws CarbonDataWriterException
   */
  public void initChannel() throws CarbonDataWriterException {
    // update the filename with new new sequence
    // increment the file sequence counter
    initFileCount();
    if (this.isInProgressExtrequired) {
      this.fileName = MessageFormat.format(this.fileNameFormat, this.fileCount)
          + CarbonCommonConstants.FILE_INPROGRESS_STATUS;
      FileData fileData = new FileData(this.fileName, this.storeLocation);
      fileManager.add(fileData);
    } else {
      this.fileName = MessageFormat.format(this.fileNameFormat, this.fileCount);
    }
    this.fileCount++;
    try {
      // open stream for new data file
      this.fileDataOutStream = FileFactory
          .getDataOutputStream(this.fileName, FileFactory.getFileType(this.fileName), (short) 1);
    } catch (FileNotFoundException fileNotFoundException) {
      throw new CarbonDataWriterException("Problem while getting the writer for Leaf File",
          fileNotFoundException);
    } catch (IOException e) {
      throw new CarbonDataWriterException("Problem while getting the writer for Leaf File", e);
    }
  }

  /**
   * Method will be used to close the open stream
   */
  public CarbonFile closeChannle() {
    CarbonUtil.closeStreams(this.fileDataOutStream);

    CarbonFile carbonFile = FileFactory.getCarbonFile(fileName, FileFactory.getFileType(fileName));

    if (!carbonFile.renameTo(fileName.substring(0, this.fileName.lastIndexOf('.')))) {
      LOGGER.info(CarbonCoreLogEvent.UNIBI_CARBONCORE_MSG,
          "file renaming failed from _0.querymerged to _0");
    }

    return carbonFile;
  }

  private int initFileCount() {
    int fileCnt = 0;
    File[] dataFiles = new File(storeLocation).listFiles(new FileFilter() {

      @Override public boolean accept(File file) {
        if (!file.isDirectory() && file.getName().startsWith(tableName) && file.getName()
            .contains(fileExtension)) {
          return true;
        }
        return false;
      }
    });
    if (dataFiles != null && dataFiles.length > 0) {
      Arrays.sort(dataFiles);
      String fileName = dataFiles[dataFiles.length - 1].getName();
      try {
        fileCnt =
            Integer.parseInt(fileName.substring(fileName.lastIndexOf('_') + 1).split("\\.")[0]);
      } catch (NumberFormatException ex) {
        fileCnt = 0;
      }
      fileCnt++;
    }
    return fileCnt;
  }

  /**
   * This method will be used to update the file channel with new file; new
   * file will be created once existing file reached the file size limit This
   * method will first check whether existing file size is exceeded the file
   * size limit if yes then write the blocklet metadata to file then set the
   * current file size to 0 close the existing file channel get the new file
   * name and get the channel for new file
   *
   * @throws CarbonDataWriterException if any problem
   */
  private void updateBlockletFileChannel() throws CarbonDataWriterException {
    // get the current file size exceeding the file size threshold
    if (currentFileSize >= fileSizeInBytes) {
      // write meta data to end of the existing file
      writeBlockletMetaDataToFile();
      // set the current file size;
      this.currentFileSize = 0;
      // close the current open file channel
      CarbonUtil.closeStreams(fileDataOutStream);
      // initialize the new channel
      initChannel();
    }
  }

  /**
   * This method will be used to write leaf data to file
   * file format
   * <key><measure1><measure2>....
   *
   * @param keyArray   key array
   * @param dataArray  measure array
   * @param entryCount number of entries
   * @param startKey   start key of leaf
   * @param endKey     end key of leaf
   * @throws CarbonDataWriterException
   * @throws CarbonDataWriterException throws new CarbonDataWriterException if any problem
   */
  public void writeDataToFile(byte[] keyArray, byte[][] dataArray, int entryCount, byte[] startKey,
      byte[] endKey) throws CarbonDataWriterException {
    if (this.isNewFileCreationRequired) {
      updateBlockletFileChannel();
    }
    // total measure length;
    int totalMsrArraySize = 0;
    // current measure length;
    int currentMsrLenght = 0;
    int[] msrLength = new int[this.measureCount];

    // calculate the total size required for all the measure and get the
    // each measure size
    for (int i = 0; i < dataArray.length; i++) {
      currentMsrLenght = dataArray[i].length;
      totalMsrArraySize += currentMsrLenght;
      msrLength[i] = currentMsrLenght;
    }
    byte[] writableDataArray = new byte[totalMsrArraySize];

    // start position will be used for adding the measure in
    // writableDataArray after adding measure increment the start position
    // by added measure length which will be used for next measure start
    // position
    int startPosition = 0;
    for (int i = 0; i < dataArray.length; i++) {
      System.arraycopy(dataArray[i], 0, writableDataArray, startPosition, dataArray[i].length);
      startPosition += msrLength[i];
    }
    writeDataToFile(keyArray, writableDataArray, msrLength, entryCount, startKey, endKey);
  }

  /**
   * This method will be used to write leaf data to file
   * file format
   * <key><measure1><measure2>....
   *
   * @param keyArray   key array
   * @param dataArray  measure array
   * @param entryCount number of entries
   * @param startKey   start key of leaf
   * @param endKey     end key of leaf
   * @throws CarbonDataWriterException
   * @throws CarbonDataWriterException throws new CarbonDataWriterException if any problem
   */
  public void writeDataToFile(byte[] keyArray, byte[] dataArray, int[] msrLength, int entryCount,
      byte[] startKey, byte[] endKey) throws CarbonDataWriterException {
    int keySize = keyArray.length;
    // write data to leaf file and get its offset
    long offset = writeDataToFile(keyArray, dataArray);

    // get the blocklet info for currently added blocklet
    BlockletInfo blockletInfo =
        getBlockletInfo(keySize, msrLength, offset, entryCount, startKey, endKey);
    // add blocklet info to list
    this.blockletInfoList.add(blockletInfo);
    // calculate the current size of the file
    this.currentFileSize +=
        keySize + dataArray.length + (blockletInfoList.size() * this.leafMetaDataSize)
            + CarbonCommonConstants.LONG_SIZE_IN_BYTE;
  }

  /**
   * This method will be used to get the blocklet metadata
   *
   * @param keySize    key size
   * @param msrLength  measure length array
   * @param offset     current offset
   * @param entryCount total number of rows in leaf
   * @param startKey   start key of leaf
   * @param endKey     end key of leaf
   * @return BlockletInfo - leaf metadata
   */
  private BlockletInfo getBlockletInfo(int keySize, int[] msrLength, long offset, int entryCount,
      byte[] startKey, byte[] endKey) {
    // create the info object for leaf entry
    BlockletInfo info = new BlockletInfo();
    // add total entry count
    info.setNumberOfKeys(entryCount);

    // add the key array length
    info.setKeyLength(keySize);

    // add key offset
    info.setKeyOffset(offset);

    // increment the current offset by adding key length to get the measure
    // offset position
    // format of metadata will be
    // <entrycount>,<keylenght>,<keyoffset>,<msr1lenght><msr1offset><msr2length><msr2offset>
    offset += keySize;

    // add measure length
    info.setMeasureLength(msrLength);

    long[] msrOffset = new long[this.measureCount];

    for (int i = 0; i < this.measureCount; i++) {
      msrOffset[i] = offset;
      // now increment the offset by adding measure length to get the next
      // measure offset;
      offset += msrLength[i];
    }
    // add measure offset
    info.setMeasureOffset(msrOffset);
    // set startkey
    info.setStartKey(startKey);
    // set end key
    info.setEndKey(endKey);
    // return leaf metadata
    return info;
  }

  /**
   * This method is responsible for writing blocklet to the data file
   *
   * @param keyArray     mdkey array
   * @param measureArray measure array
   * @return file offset offset is the current position of the file
   * @throws CarbonDataWriterException if will throw CarbonDataWriterException when any thing
   *                                   goes wrong while while writing the leaf file
   */
  private long writeDataToFile(byte[] keyArray, byte[] measureArray)
      throws CarbonDataWriterException {
    long offset = metadataOffset;
    try {
      metadataOffset += keyArray.length + measureArray.length;
      this.fileDataOutStream.write(keyArray);
      this.fileDataOutStream.write(measureArray);
    } catch (IOException exception) {
      throw new CarbonDataWriterException("Problem in writing carbon file: ", exception);
    }
    // return the offset, this offset will be used while reading the file in
    // engine side to get from which position to start reading the file
    return offset;
  }

  /**
   * This method will write metadata at the end of file file format
   * <KeyArray><measure1><measure2> <KeyArray><measure1><measure2>
   * <KeyArray><measure1><measure2> <KeyArray><measure1><measure2>
   * <entrycount>
   * <keylength><keyoffset><measure1length><measure1offset><measure2length
   * ><measure2offset>
   *
   * @throws CarbonDataWriterException throw CarbonDataWriterException when problem in
   *                                   writing the meta data to file
   */
  public void writeBlockletMetaDataToFile() throws CarbonDataWriterException {
    ByteBuffer buffer = null;
    int[] msrLength = null;
    long[] msroffset = null;
    try {
      // get the current position of the file, this will be used for
      // reading the file meta data, meta data start position in file will
      // be this position
      for (BlockletInfo info : this.blockletInfoList) {
        // get the measure length array
        msrLength = info.getMeasureLength();
        // get the measure offset array
        msroffset = info.getMeasureOffset();
        // allocate total size for buffer
        buffer = ByteBuffer.allocate(this.leafMetaDataSize);
        // add entry count
        buffer.putInt(info.getNumberOfKeys());
        // add key length
        buffer.putInt(info.getKeyLength());
        // add key offset
        buffer.putLong(info.getKeyOffset());
        // set the start key
        buffer.put(info.getStartKey());
        // set the end key
        buffer.put(info.getEndKey());
        // add each measure length and its offset
        for (int i = 0; i < this.measureCount; i++) {
          buffer.putInt(msrLength[i]);
          buffer.putLong(msroffset[i]);
        }
        // flip the buffer
        buffer.flip();
        // write metadat to file
        this.fileDataOutStream.write(buffer.array());
      }
      // create new for adding the offset of meta data
      // write offset to file
      this.fileDataOutStream.writeLong(metadataOffset);
    } catch (IOException exception) {
      throw new CarbonDataWriterException("Problem while writing the carbon file: ", exception);
    }
    // create new blocklet info list for new file
    this.blockletInfoList = new ArrayList<BlockletInfo>(CarbonCommonConstants.CONSTANT_SIZE_TEN);
  }

  /**
   * This method will be used to get the leaf meta list size
   *
   * @return list size
   */
  public int getMetaListSize() {
    return blockletInfoList.size();
  }

  public void setFileManager(IFileManagerComposite fileManager) {
    this.fileManager = fileManager;
  }

  /**
   * getFileCount
   *
   * @return int
   */
  public int getFileCount() {
    return fileCount;
  }

  /**
   * setFileCount
   *
   * @param fileCount void
   */
  public void setFileCount(int fileCount) {
    this.fileCount = fileCount;
  }
}
