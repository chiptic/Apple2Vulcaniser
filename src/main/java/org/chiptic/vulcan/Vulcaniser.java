package org.chiptic.vulcan;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;

public class Vulcaniser {
  // The Vulcan drive partition table has a magic number for recognition
  private static final int VULCAN_DISK_MAGIC_NUMBER = 0xAEAE; // Do not change

  // Standard number of bytes per block (and block is one logical disk sector)
  private static final int VULCAN_DISK_BLOCK_SIZE = 512; // Do not change

  // Maximum permitted number of active ProDOS partions
  private static final int PRODOS_MAX_ACTIVE_PARTITION_COUNT = 4; // Do not change

  // Maximum ProDOS partition size in blocks
  private static final int PRODOS_MAX_PARTITION_BLOCK_SIZE = 0xFFFF;   // 32767.5 KBytes

  // Number of maxed-out ProDOS partitions to allocate for this CF card
  // The practical usable maximum for Vulcan Partition Manager seems to be 5 maxed-out,
  // because for 6 it will show '33MB' as size where it should be '233MB'.
  // For 4 maxed-out Prodos partitions it will show '140MB' (and all 4 can be active)
  // For 5 maxed-out Prodos partitions it will show '200MB' (but only 4 can be active)
  // Because only 4 can be active it does not make much sense to want more than 5.
  private static final int VULCAN_CF_PRODOS_PARTITION_COUNT = 5;

  private static final int VULCAN_DISK_TYPE_GENERIC_20MB = 0x00;
  private static final int VULCAN_DISK_TYPE_CONNER_30104 = 0x1D;
  private static final int VULCAN_DISK_TYPE_SWIFT_200 = 0x10;

  // The drive type does not seem to matter much because the CHS mapping can be explicitly
  // defined and the Partition Manager does not recognise the CF card as a known hard drive anyway.
  // So for a nice name in Partition manager we can use the 'SWIFT 200' or 'CONNER 30104' types.
  private static final int VULCAN_CF_DRIVE_TYPE_CODE = VULCAN_DISK_TYPE_SWIFT_200;

  // Tested CHS parameters for stable usage with 4 ProDOS blocks: 1125 cyls, 6 heads, 39 tracks
  // Other track count or head count seems to cause problems on some partitions with formatting
  // or copying of files. Not sure why that is the case but 6 heads, 39 tracks seems stable.
  private static final int VULCAN_CF_DRIVE_HEAD_COUNT = 6; // Do not change

  // Number of sectors per track, using 39 seems to give stable results vor CF cards.
  private static final int VULCAN_CF_DRIVE_SECTOR_COUNT = 39; // Do not change

  // Calculate the total block count needed for full ProDOS volumes (0xFFFF blocks each)
  private static final int VULCAN_CF_DRIVE_BLOCK_COUNT = VULCAN_CF_PRODOS_PARTITION_COUNT * (PRODOS_MAX_PARTITION_BLOCK_SIZE + 1);

  // Calculate the matching cylinder count and add 5 because Vulcan seems to subtract 5 when it shows the count
  private static final int VULCAN_CF_DRIVE_CYLINDER_COUNT = 5 + (VULCAN_CF_DRIVE_BLOCK_COUNT / VULCAN_CF_DRIVE_HEAD_COUNT / VULCAN_CF_DRIVE_SECTOR_COUNT);

  // Standard interleave for fast disks (and CF cards) is 1
  private static final int VULCAN_CF_DRIVE_INTERLEAVE_COUNT = 1; // Do not change

  // Vulcan partition table starts at offset 0x100 and has 16 entries of 16 bytes each
  private static final int PARTITION_TABLE_OFFSET = 0x0100;  // Do not change
  private static final int PARTITION_TABLE_START_BLOCK = 1; // Do not change
  private static final int PARTITION_TABLE_ENTRY_SIZE = 16; // Do not change
  private static final int PARTITION_TABLE_ENTRY_COUNT = 16; // Do not change

  // Vulcan partition type codes (as shown in Vulcan Partion Manager)
  private static final int VULCAN_PARTITION_CLEAR = 0x00; // Do not change
  private static final int VULCAN_PARTITION_PRODOS = 0x01; // Do not change
  private static final int VULCAN_PARTITION_DOS3_3 = 0x02; // Do not change
  private static final int VULCAN_PARTITION_PASCAL = 0x03; // Do not change
  private static final int VULCAN_PARTITION_EA_CPM = 0x04; // Do not change

  // Vulcan partition type flags (as shown in Vulcan Partion Manager)
  private static final int VULCAN_PARTITION_ACTIVE = 0x40; // Do not change
  private static final int VULCAN_PARTITION_LOCKED = 0x80; // Do not change

  // Vulcan drive types
  /*
     00  shown as 'DRIVE ID = $00'
     01  'WESTERN DIGITAL 93028'  - WD93028 21MB 782/2/27 (615/4/17)
     02  'WESTERN DIGITAL 93048'  - WD93048 48MB 782/4/27 (977/5/17)
     03  'SWIFT'                  - CDC/Imprimis/Seagate "Swift"
     04  'SEAGATE 125' - ST125A 25MB 615/4/17
     05  'CONNER 3104'  - CP3104 104MB MB 1547/4/33 of 776/8/33 of 925/17/13
     06  'QUANTUM'
     07  'RODIME 100'
     08  'MINISCRIBE 8051' - M8051 41MB
     09  'WESTERN DIGITAL 93044' - WD93044A 43MB
     0A  'CONNER 344'  - CP344 42MB
     0B  'SEAGATE 157' - ST157A-1 45MB
     0C  'MINISCRIBE 8225' - M8225AT 21MB
     0D  'MINISCRIBE 8450' - M8450AT 42MB
     0E  'CONNER 3024'  - CP3024 20MB 615/4/17
     0F  'SWIFT 230'    - CDC (Seagate) Swift 94354-230 - 211MB  1272/9/36  (cmos 954/12/36)
     10  'SWIFT 200'    - CDC (Seagate) Swift 94354-200 - 177MB  1072/9/36  (cmos 804/12/36)
     11  'SWIFT 126'    - CDC (Seagate) Swift 94354-126 - 111MB  1072/7/29  (cmos 536/14/29)
     12  'SWIFT 090'    - CDC (Seagate) Swift 90 - 79MB 536/10/29
     13  'QUANTUM'
     14  'MAXTOR'
     15  'CONNER'
     16  'MICROSCI'
     17  'WESTERN DIGITAL AC280' - WDAC280 85MB 1082/4/39
     18  'KYOCERA'
     19  'KYOCERA'
     1A  'WESTERN DIGITAL 93024' - WD93024-A 21MB 615/4/17
     1B  'WD CAVIAR 140'  - WDAC140 42MB 1082/2/39
     1C  'WD CAVIAR 280'  - WDAC280 80MB 1082/4/39
     1D  'CONNER 30104'  - CP30104 120MB 762/8/39 (of 1522/4/39 of 1016/6/39)
     Above 1D (tested 1E .. 22) gave weird result strings or errors, so not usable
     The Conner 30104 seems to be one of the largest hard drive in this list
     and may have been the drive for the 100MB Vulcan Gold, and this settings works well
     when translated to 1125 cylinders/ 6 heads/ 39 sectors (as supplied by ebay seller CF card).
     The CDC(Seagate) Swift 200 and Swift 230 are even larger but were probably not used in Vulcans.
     None of these have the same number of heads and sectors as a Compact Flash
     card, the CFs all pretend to have 63 sectors per track and 4, 8, 16 or 32 heads.
     For example:
      CF  16MB - 124/4/63
      CF  32MB - 249/4/63
      CF  64MB - 501/4/63
      CF 128MB - 995/4/63
      CF 256MB - 990/8/63
      CF 512MB - 289/16/63
      CF   1GB - 993/32/63
      The stable drive type is 'CONNER 30104' with 6 heads and 39 sectors,
      usable cylinder counts tested: 1125 (4 partitions), 1405 (5 partitions),
      above 200MB The Vulcan Partition Manager shows wrong smaller drive size.
  */


  private final byte[] inputBuffer = new byte[8192];
  private final byte[] outputBuffer = new byte[8192];
  private final byte[] partTable = new byte[8192];

  private void readBuffer(Path inputFilePath) {
    System.out.println("-- Reading Vulcan CF Drive Partition Table --");
    try (InputStream inputStream = Files.newInputStream(inputFilePath)) {
      int count = inputStream.readNBytes(inputBuffer, 0, inputBuffer.length);
      System.out.printf("Read %d bytes from file %s\n\n", count, inputFilePath.getFileName());
    } catch (IOException ex) {
      ex.printStackTrace();
      throw new RuntimeException(ex);
    }
  }

  private void writeBuffer(Path outputFilePath) {
    System.out.println("-- Writing Vulcan CF Drive Partition Table --");
    try (OutputStream outputStream = Files.newOutputStream(outputFilePath)) {
      outputStream.write(outputBuffer, 0, outputBuffer.length);
      System.out.printf("Written %d bytes to file %s\n\n", outputBuffer.length, outputFilePath.getFileName());
    } catch (IOException ex) {
      ex.printStackTrace();
      throw new RuntimeException(ex);
    }
  }

  private void buildPartTable() {
    System.out.println("-- Building Vulcan CF Drive Partition Table --");

    // Clear partition table
    for (int i = 0; i < partTable.length; i++) {
      partTable[i] = 0;
    }
    // Set magic word
    setInt16(partTable, 0x00, VULCAN_DISK_MAGIC_NUMBER);

    // Initialize sequence of values that we later partially override
    setByteSequence(partTable, 0x04, new int[] {0x1D, 0x1E, 0x9C, 0x03, 0x00, 0xF8, 0x03, 0x06, 0x27, 0x01, 0x00});
    setByteRepeated(partTable, 0x0F, 0xFF, 14);

    // Adapt drive id
    setInt24(partTable, 0x04, VULCAN_CF_DRIVE_TYPE_CODE);
    // Adapt drive block count
    setInt24(partTable, 0x05, VULCAN_CF_DRIVE_BLOCK_COUNT);
    // Adapt drive cylinder count (set to 5 bigger than required for number of blocks)
    setInt16(partTable, 0x09, VULCAN_CF_DRIVE_CYLINDER_COUNT);
    // Adapt drive head count (this value taken from working Transcend1GB CF example)
    setInt8(partTable, 0x0B, VULCAN_CF_DRIVE_HEAD_COUNT);
    // Adapt drive sector count (this value taken from working Transcend1GB CF example)
    setInt8(partTable, 0x0C, VULCAN_CF_DRIVE_SECTOR_COUNT);
    // Adapt drive interleave (can be set via partition manager application)
    setInt8(partTable, 0x0D,VULCAN_CF_DRIVE_INTERLEAVE_COUNT);
    // Set boot partition index (can be set via partition manager application)
    setInt8(partTable, 0x0E, 0);

    // Set ProDOS boot partitions (will be set via partition manager application)
    for (int i = 0; i < 4; i++) {
      //  setInt8(partTable, 0x11 + i, PRODOS_MAX_ACTIVE_PARTITION_COUNT > i ? i : 0xFF);
      setInt8(partTable, 0x11 + i, 0xFF);
    }

    // Initialize junk values (probably not needed but present on the example Vulcan CF card)
    //setByteRepeated(partTable, 0x8E, 0xF3, 6);
    //setByteRepeated(partTable, 0x94, 0xF4, 6);
    //setByteRepeated(partTable, 0x9A, 0xF5, 6);
    //setByteRepeated(partTable, 0xA0, 0xF6, 2);
    //int[] junkSequence = {0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D};
    //setByteSequence(partTable, 0xA2, junkSequence);
    //setByteSequence(partTable, 0xA8, junkSequence);
    //setByteSequence(partTable, 0xAE, junkSequence);
    //setByteSequence(partTable, 0xB4, new int[] {0x18,0x19});

    // Initialize unknown values
    setByteSequence(partTable, 0xB6, new int[] {0x00,0x80,0x01});

    // Number of free blocks should be same as total available disk blocks minus the partion block
    // Maximum ProDOS partition size is 32MB, 32767.5 KBytes, 65535 blocks.
    int freeBlocks = ((PRODOS_MAX_PARTITION_BLOCK_SIZE + 1) * VULCAN_CF_PRODOS_PARTITION_COUNT) - 1;
    if (VULCAN_CF_DRIVE_BLOCK_COUNT != freeBlocks + 1) {
      System.out.printf("\nWARNING: expected VULCAN_CF_DRIVE_BLOCK_COUNT == freeBlocks + 1: 0x%04X 0x%06X", VULCAN_CF_DRIVE_BLOCK_COUNT, freeBlocks);
    }

    int start = PARTITION_TABLE_START_BLOCK;
    int size = PRODOS_MAX_PARTITION_BLOCK_SIZE;
    for (int i = 0; i < PARTITION_TABLE_ENTRY_COUNT; i++) {
      int partBase = PARTITION_TABLE_OFFSET + (i * PARTITION_TABLE_ENTRY_SIZE);
        if (freeBlocks < size) {
        size = freeBlocks;
      }
      freeBlocks -= size;

      int type = VULCAN_PARTITION_CLEAR;
      if (i == 0 || size == PRODOS_MAX_PARTITION_BLOCK_SIZE) {
//        type = VULCAN_PARTITION_PRODOS ;
        if (i < PRODOS_MAX_ACTIVE_PARTITION_COUNT) {
          // Maximum of 4 ProDOS partitions can be active
          type |= VULCAN_PARTITION_ACTIVE;
        }
      }

      // Partition entry contents (16 bytes):
      // offset 0 : 24-bits partition start
      // offset 3 : 16-bits partition size
      // offset 5 : 8-bits partition type
      // offset 6 : 10-bytes partition name
      setInt24(partTable, partBase + 0, start);
      setInt16(partTable, partBase + 3, size);
      setInt8(partTable, partBase + 5, type);
      start += size;

      // Create default names for each partition
      String name = String.format("AE%d%10s", i + 1,"");
      for (int j = 0; j < 10; j++) {
        // Set high bit of each character according to Apple II convention
        setInt8(partTable, partBase + 6 + j, name.charAt(j) | 0x80);
      }
    }

    // Set checksum (seems to be ignored by version 2.02 of Partion Manager Gold ?)
    setInt16(partTable, 0x02, calculateCheckSum());
    System.out.println("created Vulcan CF Drive Partition Block in buffer");
    System.out.println();
  }

  private void setByteSequence(byte[] buf, int ofs, int[] values) {
    for (int i = 0; i < values.length; i++) {
      setInt8(buf, ofs + i, values[i]);
    }
  }

  private void setByteRepeated(byte[] buf, int ofs, int value, int count) {
    for (int i = 0; i < count; i++) {
      setInt8(buf, ofs + i, value);
    }
  }

  void convertFromInterleaved(byte[] interleavedInput, byte[] partTableBuffer) {
    int k = 0;
    for (int i = 0; i < 256; i += 2) {
      for (int j = i; j < VULCAN_DISK_BLOCK_SIZE; j += 256) {
        //System.out.printf("\nk = 0x%04X (%d), i = 0x%02X (%d), j = 0x%04X (%d)", k, k, i, i, j, j);
        byte b0 = interleavedInput[k];
        byte b1 = interleavedInput[k + 1];
        partTableBuffer[j] = b0;
        partTableBuffer[j + 1] = b1;
        k += 2;
      }
    }
  }

  void convertToInterleaved(byte[] partTableBuffer, byte[] interleavedOutput) {
    // Clear output buffer
    for (int i = 0; i < interleavedOutput.length; i++) {
      interleavedOutput[i] = 0;
    }
    int k = 0;
    for (int i = 0; i < 256; i += 2) {
      for (int j = i; j < VULCAN_DISK_BLOCK_SIZE; j += 256) {
        //System.out.printf("\nk = 0x%04X (%d), i = 0x%02X (%d), j = 0x%04X (%d)", k, k, i, i, j, j);
        if (j >= 512) {
          throw new IllegalStateException("index j out of range: " + j);
        }
        byte b0 = partTableBuffer[j];
        byte b1 = partTableBuffer[j + 1];
        if (k >= 512) {
          throw new IllegalStateException("index k out of range: " + k);
        }
        interleavedOutput[k] = b0;
        interleavedOutput[k + 1] = b1;
        k += 2;
      }
    }
    for (int i = 512; i < interleavedOutput.length; i++) {
      if (interleavedOutput[i] != 0) {
        throw new IllegalStateException("interleavedOutput[i] not nul: " + i);
      }
    }
  }

  int getInt8(byte[] buf, int ofs) {
    return buf[ofs] & 0xff;
  }

  int getInt16(byte[] buf, int ofs) {
    return (getInt8(buf, ofs + 1) << 8) | getInt8(buf, ofs);
  }

  int getInt24(byte[] buf, int ofs) {
    int a = getInt8(buf, ofs + 2);
    int b = getInt8(buf, ofs + 1);
    int c = getInt8(buf, ofs);
    int res =  a << 16 | b << 8 | c;
    return res;
  }

  void setInt8(byte[] buf, int ofs, byte val) {
    buf[ofs] = val;
  }

  void setInt8(byte[] buf, int ofs, int val) {
    buf[ofs] = (byte) (val & 0xff);
  }

  void setInt16(byte[] buf, int ofs, int val) {
    setInt8(buf, ofs + 1, (byte) ((val >> 8) & 0xff));
    setInt8(buf, ofs, (byte) (val & 0xff));
  }

  void setInt24(byte[] buf, int ofs, int val) {
    setInt8(buf, ofs + 2, (byte) ((val >> 16) & 0xff));
    setInt8(buf, ofs + 1, (byte) ((val >> 8) & 0xff));
    setInt8(buf, ofs, (byte) (val & 0xff));
  }

  String getString(byte[] buf, int ofs, int len) {
    StringBuffer sb = new StringBuffer();
    for (int i = 0; i < len; i++) {
      char ch = (char) (buf[ofs + i] & 0x7f);
      if (ch < ' ' || ch > '~') {
        ch = '_';
      }
      sb.append(ch);
    }
    return sb.toString();
  }

  String getHexBytes(byte[] buf, int ofs, int len) {
    return getHexBytes(buf, ofs, len, false);
  }

  String getDummyBytes(byte[] buf, int ofs, int len) {
    return getHexBytes(buf, ofs, len, true);
  }

  String getHexBytes(byte[] buf, int ofs, int len, boolean skip) {
    StringBuffer sb = new StringBuffer();
    for (int i = 0; i < len; i++) {
      int b = (buf[ofs + i] & 0xff);
      String hex = String.format("0x%02X", b);
      if (b == 0 && skip) {
        sb.append('.');
      } else {
        if (i > 0) {
          sb.append(',');
        }
        sb.append(hex);
      }
    }
    return sb.toString();
  }


  private void analyseBuffer() {
    System.out.println("-- Analysing Vulcan CF Drive Partition Table --");

    int magic;
    int checksum;
    int driveBlockCount;
    int driveCylinderCount;
    int driveHeadCount;
    int driveSectorCount;
    int bootPartitionIndex;

    int ofs = 0;

    System.out.printf("Vulcan drive partion block data:");

    // 16-bit Magic number hexadecimal AEAE for "Applied Engineering"
    int step = 2;
    System.out.printf("\nofs: 0x%02X size: 0x%02X  ", ofs, step);
    magic = getInt16(partTable, ofs);
    System.out.printf("magic: 0x%04X", magic);
    ofs += step;

    // 16-bit Checksum number
    step = 2;
    System.out.printf("\nofs: 0x%02X size: 0x%02X  ", ofs, step);
    checksum = getInt16(partTable, ofs);
    System.out.printf("chksum: 0x%04X", checksum);
    ofs += step;

    // 8-bit drive type code, 0x00 for '20M' drive, 0x1D for 'CONNER 30104' drive
    step = 1;
    System.out.printf("\nofs: 0x%02X size: 0x%02X  ", ofs, step);
    System.out.printf("drv-id: 0x%02X (%d)", getInt8(partTable, ofs), getInt8(partTable, ofs));
    ofs += step;

    // 24-bit total drive total block count
    step = 3;
    System.out.printf("\nofs: 0x%02X size: 0x%02X  ", ofs, step);
    driveBlockCount = getInt24(partTable, ofs);
    System.out.printf("blocks: 0x%06X (%d)", driveBlockCount, driveBlockCount);
    ofs += step;

    // unknown
    step = 1;
    System.out.printf("\nofs: 0x%02X size: 0x%02X  ", ofs, step);
    System.out.printf("unknown: %s", getHexBytes(partTable, ofs, step));
    ofs += step;

    // 16-bit drive cylinder count
    step = 2;
    System.out.printf("\nofs: 0x%02X size: 0x%02X  ", ofs, step);
    driveCylinderCount = getInt16(partTable, ofs);
    System.out.printf("cylinders: 0x%04X (%d)", driveCylinderCount, driveCylinderCount);
    ofs += step;

    // 8-bit drive head count
    step = 1;
    System.out.printf("\nofs: 0x%02X size: 0x%02X  ", ofs, step);
    driveHeadCount = getInt8(partTable, ofs);
    System.out.printf("heads: 0x%02X (%d)", driveHeadCount, driveHeadCount);
    ofs += step;

    // 8-bit drive sector count
    step = 1;
    System.out.printf("\nofs: 0x%02X size: 0x%02X  ", ofs, step);
    driveSectorCount = getInt8(partTable, ofs);
    System.out.printf("sectors: 0x%02X (%d)", driveSectorCount, driveSectorCount);
    ofs += step;

    // 8-bit interleave count
    step = 1;
    System.out.printf("\nofs: 0x%02X size: 0x%02X  ", ofs, step);
    System.out.printf("interleave: 0x%02X (%d)", getInt8(partTable, ofs), getInt8(partTable, ofs));
    ofs += step;

    // 8-bit boot partition index
    step = 1;
    System.out.printf("\nofs: 0x%02X size: 0x%02X  ", ofs, step);
    bootPartitionIndex = getInt8(partTable, ofs);
    System.out.printf("boot-partition: 0x%02X (%02d)", bootPartitionIndex, bootPartitionIndex);
    ofs += step;

    // unknown
    step = 2;
    System.out.printf("\nofs: 0x%02X size: 0x%02X  ", ofs, step);
    System.out.printf("unknown: %s", getDummyBytes(partTable, ofs, step));
    ofs += step;

    // probably active ProDOS partition 1
    step = 1;
    System.out.printf("\nofs: 0x%02X size: 0x%02X  ", ofs, step);
    System.out.printf("ProDOS-partition1: 0x%02X (%02d)", getInt8(partTable, ofs), getInt8(partTable, ofs));
    ofs += step;

    // probably active ProDOS partition 2
    step = 1;
    System.out.printf("\nofs: 0x%02X size: 0x%02X  ", ofs, step);
    System.out.printf("ProDOS-partition2: 0x%02X (%02d)", getInt8(partTable, ofs), getInt8(partTable, ofs));
    ofs += step;

    // probably active ProDOS partition 3
    step = 1;
    System.out.printf("\nofs: 0x%02X size: 0x%02X  ", ofs, step);
    System.out.printf("ProDOS-partition3: 0x%02X (%02d)", getInt8(partTable, ofs), getInt8(partTable, ofs));
    ofs += step;

    // probably active ProDOS partition 4
    step = 1;
    System.out.printf("\nofs: 0x%02X size: 0x%02X  ", ofs, step);
    System.out.printf("ProDOS-partition4: 0x%02X (%02d)", getInt8(partTable, ofs), getInt8(partTable, ofs));
    ofs += step;

    // unknown
    step = 8;
    System.out.printf("\nofs: 0x%02X size: 0x%02X  ", ofs, step);
    System.out.printf("unknown: %s", getHexBytes(partTable, ofs, step));
    ofs += step;

    // sector interleave skew steps for all sectors (39 bytes filled for example when interleave is set to 2)
    // these are all zero when interleave step is 1 but get filled when it is set to 2
    // like such: 0x01,0x03,0x05,0x07,0x09,0x0B,0x0D,0x0F,0x11,0x13,0x15,0x17,0x19,0x1B,0x1D,0x1F,0x21,0x23,
    // 0x25,0x27,0x02,0x04,0x06,0x08,0x0A,0x0C,0x0E,0x10,0x12,0x14,0x16,0x18,0x1A,0x1C,0x1E,0x20,0x22,0x24,0x26
    // Maybe not all are used for sector skew, some disks have up to 65 sectors per track
    step = 113;
    System.out.printf("\nofs: 0x%02X size: 0x%02X  ", ofs, step);
    System.out.printf("sector skew list: %s", getDummyBytes(partTable, ofs, step));
    ofs += step;

    // unknown
    step =  6;
    System.out.printf("\nofs: 0x%02X size: 0x%02X  ", ofs, step);
    System.out.printf("unknown: %s", getDummyBytes(partTable, ofs, step));
    ofs += step;

    // unknown
    step =  6;
    System.out.printf("\nofs: 0x%02X size: 0x%02X  ", ofs, step);
    System.out.printf("unknown: %s", getDummyBytes(partTable, ofs, step));
    ofs += step;

    // unknown
    step =  6;
    System.out.printf("\nofs: 0x%02X size: 0x%02X  ", ofs, step);
    System.out.printf("unknown: %s", getDummyBytes(partTable, ofs, step));
    ofs += step;

    // unknown
    step =  2;
    System.out.printf("\nofs: 0x%02X size: 0x%02X  ", ofs, step);
    System.out.printf("unknown: %s", getDummyBytes(partTable, ofs, step));
    ofs += step;

    // unknown
    step =  6;
    System.out.printf("\nofs: 0x%02X size: 0x%02X  ", ofs, step);
    System.out.printf("unknown: %s", getDummyBytes(partTable, ofs, step));
    ofs += step;

    // unknown
    step =  6;
    System.out.printf("\nofs: 0x%02X size: 0x%02X  ", ofs, step);
    System.out.printf("unknown: %s", getDummyBytes(partTable, ofs, step));
    ofs += step;

    // unknown
    step =  6;
    System.out.printf("\nofs: 0x%02X size: 0x%02X  ", ofs, step);
    System.out.printf("unknown: %s", getDummyBytes(partTable, ofs, step));
    ofs += step;

    // unknown
    step = 2;
    System.out.printf("\nofs: 0x%02X size: 0x%02X  ", ofs, step);
    System.out.printf("unknown: %s", getDummyBytes(partTable, ofs, step));
    ofs += step;

    // unknown data
    step = 3;
    System.out.printf("\nofs: 0x%02X size: 0x%02X  ", ofs, step);
    System.out.printf("unknown: %s", getDummyBytes(partTable, ofs, step));
    ofs += step;

    // empty
    step = 7;
    System.out.printf("\nofs: 0x%02X size: 0x%02X  ", ofs, step);
    System.out.printf("empty: %s", getDummyBytes(partTable, ofs, step));
    ofs += step;

    // empty
    step = 4*16;
    System.out.printf("\nofs: 0x%02X size: 0x%02X  ", ofs, step);
    System.out.printf("empty: %s", getDummyBytes(partTable, ofs, step));
    ofs += step;

    // Check if accumulated offset is now correctly at 0x100
    if (ofs != PARTITION_TABLE_OFFSET) {
      throw new IllegalStateException(String.format("ERROR: expected ofs == 0x%02X: 0x%02X", PARTITION_TABLE_OFFSET, ofs));
    }

    int calculatedCylinders = driveBlockCount / driveHeadCount / driveSectorCount;
    System.out.printf("\nCalculated cylinder count: 0x%04X (%d)", calculatedCylinders, calculatedCylinders);
    int calculatedLogicalDriveSize =  (driveBlockCount  * VULCAN_DISK_BLOCK_SIZE);
    BigDecimal logicalDriveSizeDec = new BigDecimal(calculatedLogicalDriveSize).divide(new BigDecimal(1024 * 1024), RoundingMode.HALF_UP).setScale(1, RoundingMode.HALF_UP);
    System.out.printf("\nCalculated logical drive size (by blocks): %sMB", logicalDriveSizeDec);
    int calculatedNativeDriveSize =  (driveCylinderCount * driveHeadCount * driveSectorCount * 512);
    BigDecimal nativeDriveSizeDec = new BigDecimal(calculatedNativeDriveSize).divide(new BigDecimal(1024 * 1024), RoundingMode.HALF_UP).setScale(1, RoundingMode.HALF_UP);
    System.out.printf("\nCalculated native drive size (by cylinders): %sMB", nativeDriveSizeDec);

    // Print the partition table (16 x 16 bytes)
    // name is 10 bytes ASCII right-padded with spaces
    System.out.printf("\nPartion table entries:");
    System.out.printf("\nINDEX:, LOCK:, NAME:     , START:  , SIZE:            , ON:, TYPE:, BOOT:");

    int block = PARTITION_TABLE_START_BLOCK;
    int partionBlocksCount = 1;
    for (int i = 0; i < PARTITION_TABLE_ENTRY_COUNT; i++) {

      int partBase = PARTITION_TABLE_OFFSET + (i * PARTITION_TABLE_ENTRY_SIZE);
      int start = getInt24(partTable, partBase);
      int size = getInt16(partTable,partBase + 3);
      int code = getInt8(partTable, partBase + 5);
      String name = getString(partTable, partBase + 6, 10);
      boolean active = (code & VULCAN_PARTITION_ACTIVE) != 0;
      boolean locked = (code & VULCAN_PARTITION_LOCKED) != 0;

      String type;
      switch (code & 0x0f) {
        case VULCAN_PARTITION_CLEAR:
          type = "CLEAR ";
          break;
        case VULCAN_PARTITION_PRODOS:
          type = "PRODOS";
          break;
        case VULCAN_PARTITION_DOS3_3:
          type = "DOS3.3";
          break;
        case VULCAN_PARTITION_PASCAL:
          type = "PASCAL";
          break;
        case VULCAN_PARTITION_EA_CPM:
          type = "CP/M  ";
          break;
        default:
          type = String.format("UNKNOWN: 0x%02X", code);
      }

      BigDecimal space = new BigDecimal(size).multiply(new BigDecimal(VULCAN_DISK_BLOCK_SIZE));
      if (size > 0) {
        space = space.divide(new BigDecimal(1024), RoundingMode.HALF_UP);
      }
      space = space.setScale(1, RoundingMode.HALF_EVEN);

      System.out.printf("\n 0x%02X (%2d)", i, i + 1);
      System.out.printf(", %1s", locked ? "*" : "");
      System.out.printf(", %10s", name);
      System.out.printf(", 0x%06X", start);
      System.out.printf(", 0x%04X (%7sK)", size, space);
      System.out.printf(", %2s ", active ? "*" : "");
      System.out.printf(", %6s", type);
      System.out.printf(", %-2s", i == bootPartitionIndex ? "*" : "");
      //System.out.printf(" [%s]", getHexBytes(partTable, partBase, 16));

      if (start != block) {
        System.out.printf("\nERROR: expected start == block: 0x%06X 0x%06X", start, block);
      }
      block = start + size;
      partionBlocksCount += size;
    }

    // Check that rest of partition table buffer space is still cleared
    ofs = PARTITION_TABLE_OFFSET + (PARTITION_TABLE_ENTRY_COUNT * PARTITION_TABLE_ENTRY_SIZE);
    for (int i = ofs; i < partTable.length; i++) {
      if (partTable[i] != 0) {
        throw new IllegalStateException("ERROR: buffer space partTable[i] not nul: " + i);
      }
    }

    // Check if magic number is correct
    if (magic != VULCAN_DISK_MAGIC_NUMBER) {
      throw new IllegalStateException(String.format("ERROR: expected magic == 0x%02X: 0x%02X", VULCAN_DISK_MAGIC_NUMBER, magic));
    }

    // Check if checksum is as expected
    int checkedsum = calculateCheckSum();
    if (checksum != checkedsum) {
      System.out.printf("\nWARNING: expected checksum == checkedsum: 0x%04X 0x%04X", checksum, checkedsum);
    }

    // Check that counted blocks size is same as registered total block size
    if (partionBlocksCount != driveBlockCount) {
      System.out.printf("\nWARNING: expected partionBlocksCount == driveBlockCount: 0x%04X 0x%06X", partionBlocksCount, driveBlockCount);
    }

    // Check that registered total block size divided by heads and sectors matches registered cylinders
    if (calculatedCylinders > driveCylinderCount) {
      System.out.printf("\nWARNING: expected calculatedCylinders > driveCylinderCount: 0x%04X 0x%06X", calculatedCylinders, driveCylinderCount);
    }
    System.out.printf("\n\n");
  }

  /*
    This checksum calculation is taken from example C code 'part_vulcan.c', but
    probably not correct because it does not match checksums created by partition table changes.
    However it seems that Vulcan Gold partition table manager does not check these.
   */
  private int calculateCheckSum() {
    byte cksumb0 = 0;
    byte cksumb1 = 0;
    for (int i = 0; i < VULCAN_DISK_BLOCK_SIZE; i += 2) {
      // Skip checksum in calculation ?
      if (i == 0x02) {
        continue;
      }
      cksumb0 ^= partTable[i];
      cksumb1 ^= partTable[i+1];
    }
    return (((int) cksumb1) & 0xff) << 8 | (int) cksumb0 & 0xff;
  }


  private void run(Path workDirPath ) {
    //Path inputFilePath = workDirPath.resolve("transcend256mb_test3.img");
    //Path inputFilePath = workDirPath.resolve("transcend256mb.img");
    //Path inputFilePath = workDirPath.resolve("apacer1gb_check2.img");
    //Path inputFilePath = workDirPath.resolve("transcend1gb_vulcan.img");
    Path inputFilePath = workDirPath.resolve("vulcan_cf5.img");

    //Path inputFilePath = workDirPath.resolve("cf16mbvulcan_lexar.img");

    Path outputFilePath = workDirPath.resolve("vulcan_part.img");

    readBuffer(inputFilePath);
    convertFromInterleaved(inputBuffer, partTable);

    convertToInterleaved(partTable, outputBuffer);
    convertFromInterleaved(outputBuffer, partTable);

    analyseBuffer();

    buildPartTable();

    convertToInterleaved(partTable, outputBuffer);
    convertFromInterleaved(outputBuffer, partTable);

    analyseBuffer();

    writeBuffer(outputFilePath);

    readBuffer(outputFilePath);
    convertFromInterleaved(inputBuffer, partTable);
    convertToInterleaved(partTable, outputBuffer);
    convertFromInterleaved(outputBuffer, partTable);
    analyseBuffer();

  }

  public static void main(String[] args) {

    //Path userHomeDir = Path.of(System.getProperty("user.home"));
    Path userHomeDir = Path.of("d:/projects/");
    new Vulcaniser().run(userHomeDir.resolve("vulcan"));
  }
}
