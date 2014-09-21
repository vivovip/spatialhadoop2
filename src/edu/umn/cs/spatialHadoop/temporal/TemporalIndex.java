/**
 * 
 */
package edu.umn.cs.spatialHadoop.temporal;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;

import edu.umn.cs.spatialHadoop.core.SpatialSite;
import edu.umn.cs.spatialHadoop.io.TextSerializable;
import edu.umn.cs.spatialHadoop.io.TextSerializerHelper;

/**
 * Stores and retrieves a temporal index which partitions data into disjoint
 * temporal partitions each stored in a separate directory.
 * @author Ahmed Eldawy
 *
 */
public class TemporalIndex {
  /***
   * Stores the information of one partition in the temporal index.
   * @author eldawy
   *
   */
  public static class TemporalPartition implements TextSerializable,
      Comparable<TemporalPartition> {
    /**Start time for this partition*/
    public long start;
    /**End time for this partition*/
    public long end;
    /**Name of the directory that contains data of this partition*/
    public String dirName;

    public TemporalPartition() {}

    @Override
    public Text toText(Text text) {
      TextSerializerHelper.serializeLong(start, text, ',');
      TextSerializerHelper.serializeLong(end, text, ',');
      byte[] strBytes = dirName.getBytes();
      text.append(strBytes, 0, strBytes.length);
      return text;
    }

    @Override
    public void fromText(Text text) {
      start = TextSerializerHelper.consumeLong(text, ',');
      end = TextSerializerHelper.consumeLong(text, ',');
      dirName = text.toString();
    }

    @Override
    public int compareTo(TemporalPartition o) {
      if (this.start < o.start)
        return -1;
      if (this.start > o.start)
        return 1;
      return 0;
    }

    /**
     * Returns true if and only if the given time is inside the range of this
     * partition.
     * @param time
     * @return
     */
    public boolean contains(long time) {
      return time >= start && time < end;
    }
  }
  
  /**
   * All temporal partitions in this index sorted by time.
   */
  private TemporalPartition[] partitions;
  
  /**
   * The path to the directory that contains all partitions.
   */
  private Path path;

  /**Pattern for date format of a day as it appears in NASA LP DAAC archive*/
  final Pattern DayPattern = Pattern.compile("^\\d{4}\\.\\d{2}\\.\\d{2}$");
  final SimpleDateFormat DayFormat = new SimpleDateFormat("yyyy.MM.dd");
  final Pattern MonthPattern = Pattern.compile("^\\d{4}\\.\\d{2}$");
  final SimpleDateFormat MonthFormat = new SimpleDateFormat("yyyy.MM");
  final Pattern YearPattern = Pattern.compile("^\\d{4}$");
  final SimpleDateFormat YearFormat = new SimpleDateFormat("yyyy");
  /**
   * Constructs a temporal index on the fly given a directory that follows
   * a standard naming convention for subdirectories.
   * For a partitions which spans a whole day, the name is 'yyyy.mm.dd'.
   * For a partitions which spans a whole month, the name is 'yyyy.mm'.
   * For a partitions which spans a whole year, the name is 'yyyy'.
   * 
   * @param path
   * @throws IOException 
   * @throws ParseException 
   */
  public TemporalIndex(FileSystem fs, Path path) throws IOException, ParseException {
    this.path = path;
    FileStatus[] subdirs = fs.listStatus(path, SpatialSite.NonHiddenFileFilter);
    Calendar calendar = Calendar.getInstance();
    // Initialize partitions
    this.partitions = new TemporalPartition[subdirs.length];
    for (int i = 0; i < subdirs.length; i++) {
      FileStatus subdir = subdirs[i];
      partitions[i] = new TemporalPartition();
      partitions[i].dirName = subdir.getPath().getName();
      Matcher matcher = DayPattern.matcher(partitions[i].dirName);
      if (matcher.matches()) {
        // Day
        Date date = DayFormat.parse(partitions[i].dirName);
        calendar.setTime(date);
        partitions[i].start = calendar.getTimeInMillis();
        calendar.add(Calendar.DAY_OF_MONTH, 1);
        partitions[i].end = calendar.getTimeInMillis();
        continue;
      } 
      matcher = MonthPattern.matcher(partitions[i].dirName);
      if (matcher.matches()) {
        // Month
        Date date = MonthFormat.parse(partitions[i].dirName);
        calendar.setTime(date);
        partitions[i].start = calendar.getTimeInMillis();
        calendar.add(Calendar.MONTH, 1);
        partitions[i].end = calendar.getTimeInMillis();
        continue;
      }
      matcher = YearPattern.matcher(partitions[i].dirName);
      if (matcher.matches()) {
        // Month
        Date date = YearFormat.parse(partitions[i].dirName);
        calendar.setTime(date);
        partitions[i].start = calendar.getTimeInMillis();
        calendar.add(Calendar.YEAR, 1);
        partitions[i].end = calendar.getTimeInMillis();
        continue;
      }
      throw new RuntimeException("Cannot detect time range for directory: '" +
          subdir.getPath()+"'");
    }
    // Sort partitions based on time
    Arrays.sort(this.partitions);
  }
  
  /**
   * Select all partitions that overlap a temporal query range given as start
   * and end times.
   * @param start
   * @param end
   * @return
   */
  public TemporalPartition[] selectOverlap(long start, long end) {
    // Perform a binary search to find the matching range of partitions
    int startIndex = binarySearch(start);
    int endIndex = binarySearch(end);
    // Make sure that all overlapping partitions are contained in the range
    // [startIndex, endIndex); open ended
    if (endIndex < this.partitions.length
        && this.partitions[endIndex].contains(end))
      endIndex++;
    if (startIndex >= endIndex)
      return null; // No matches
    TemporalPartition[] matches = new TemporalPartition[endIndex - startIndex];
    System.arraycopy(this.partitions, startIndex, matches, 0, matches.length);
    return matches;
  }
  
  /**
   * Select all partitions that are totally contained in a given time range
   * @param start
   * @param end
   * @return - All matching partitions or <code>null</code> if non are matched
   */
  public TemporalPartition[] selectContained(long start, long end) {
    // Perform a binary search to find the matching range of partitions
    int startIndex = binarySearch(start);
    // If startIndex points to a partially overlapping partition, skip it and
    // match the next one (which has to be totally contained in given range)
    if (startIndex < this.partitions.length
        && this.partitions[startIndex].start > start)
      startIndex++;
    // If endIndex points to a partition that is totally contained, include
    // it in the range by incrementing endIndex
    int endIndex = binarySearch(end);
    if (endIndex < this.partitions.length
        && this.partitions[endIndex].start > end)
      endIndex++;
    if (startIndex >= endIndex)
      return null; // No matches
    TemporalPartition[] matches = new TemporalPartition[endIndex - startIndex];
    System.arraycopy(this.partitions, startIndex, matches, 0, matches.length);
    return matches;
  }

  /**
   * Perform a binary search in the sorted array of partitions to find the
   * index in which this time can be inserted while keeping the array sorted.
   * If the given point falls in one of the partitions, the index of this
   * partition is returned. If it does not overlap with any partition, the
   * index of the first partition that follows the given time is returned.
   * @param end
   * @return
   */
  private int binarySearch(long time) {
    int s = 0;
    int e = this.partitions.length;
    while (s < e) {
      int m = (s + e) / 2;
      if (this.partitions[m].contains(time)) {
        return m;
      }
      if (this.partitions[m].start > time) {
        e = m;
      } else {
        s = m + 1;
      }
    }
    return s;
  }
}