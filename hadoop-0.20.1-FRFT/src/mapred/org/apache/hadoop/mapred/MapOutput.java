package org.apache.hadoop.mapred;

import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.ReduceTask.ReduceCopier.MapOutputLocation;


public class MapOutput {

	private TaskAttemptID reduceAttemptId = null;// Used in HashOutputCopier
	private MapOutputLocation mapOutputLocation = null; // Used for retries

	private Path file;

	Configuration conf;

	private byte[] data;
	private int bytesRead;

	private boolean inMemory;

	TaskAttemptID taskAttemptID;

	// It's the generated from the data retrieved from the RT.
	// It's the hash generated from the data[]
	private String generatedHash;

	// says if this is a good copy
	private boolean valid = false;

	// The size of hashOutputList is the size of number of replicas of a Map
	//
	// The index of the Map<Integer, List<HashOutput>> is a sequential number built  like
	// (mapId*nrReplicas) + nrOfReplica
	//
	// List<HashOutput> contains the *hash of all partitions* of a map replica. If a map output
	// contains 4 partitions, the size of the list will be 4. In this case, the partition 0 will be
	// in the first position, the partition 1 in the next and so forth.
	private static final Log LOG = LogFactory.getLog(MapOutput.class.getName());

	// Used in HashOutputCopier
	public MapOutput(TaskAttemptID reduceAttemptId, MapOutputLocation mapOutputLocation) {
		this.reduceAttemptId = reduceAttemptId;
		this.mapOutputLocation = mapOutputLocation;
		this.file = null;
		this.conf = null;
	}

	public MapOutput(TaskAttemptID mapAttemptId, Configuration conf, Path file, int size) {
		this.taskAttemptID = mapAttemptId;

		this.conf = conf;
		this.file = file;
		
		this.data = null;

		this.inMemory = false;
	}

	public MapOutput(TaskAttemptID reduceAttemptId, TaskAttemptID mapAttemptId, byte[] data, MapOutputLocation mapOutputLocation, int bytesRead) {
		this.reduceAttemptId = reduceAttemptId;
		this.taskAttemptID = mapAttemptId;

		this.file = null;
		this.conf = null;

		this.data = data;
		//		this.size = compressedLength;
		this.bytesRead = bytesRead;

		this.inMemory = true;
	}



	public void discard()
	throws IOException {
		if (inMemory) {
			LOG.debug("Removing mapoutput from memory");
			data = null;
		} else {
			LOG.debug("Removing mapoutput file : " + file);
			FileSystem fs = file.getFileSystem(conf);
			fs.delete(file, true);
		}
	}

	public void setHash(String hash) {
		this.generatedHash = hash;
	}

	public String getHash() {
		return generatedHash;
	}

	public void validMapOutput() {
		valid = true;
	}

	public void invalidMapOutput() {
		valid = false;
	}

	public boolean isValidMapOutput() {
		return valid;
	}

	public TaskAttemptID getReduceAttemptId() {
		return reduceAttemptId;
	}

	public void setReduceAttemptId(TaskAttemptID reduceAttemptId) {
		this.reduceAttemptId = reduceAttemptId;
	}

	public MapOutputLocation getMapOutputLocation() {
		return mapOutputLocation;
	}

	public void setMapOutputLocation(MapOutputLocation mapOutputLocation) {
		this.mapOutputLocation = mapOutputLocation;
	}

	public byte[] getData() {
		return data;
	}

	public void setData(byte[] data) {
		this.data = data;
	}

	public void setInMemory() {
		inMemory = true;
	}
	
	public boolean isInMemory() {
		return inMemory;
	}

	public int getBytesRead() {
		return bytesRead;
	}

	public void setBytesRead(int bytesRead) {
		this.bytesRead = bytesRead;
	}

	public Path getFile() {
		return file;
	}

	public TaskAttemptID getTaskAttemptID() {
		return taskAttemptID;
	}
}
