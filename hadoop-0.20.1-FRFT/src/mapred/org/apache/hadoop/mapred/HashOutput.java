package org.apache.hadoop.mapred;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableUtils;

public class HashOutput implements Writable {
	private static final long serialVersionUID = 5897047086444071548L;

	private String mapId;
	private int partition;
	private String hash; // |0| #1#  !d46da64ab99861121d069748d2f4b695a69b6728!

	public HashOutput() {}


	public HashOutput(String mapId, int partition, String hash) {
		this.mapId = mapId;// attempt_201005130119_0001_m_000000_0_0_m_0
		this.partition = partition;
		this.hash = hash;
	}

	/**
	 * return the latest hash saved
	 * @return
	 */
	public String getHash() {
		return hash;
	}

	public String getMapId() {
		return mapId;
	}

	public int getPartition() {
		return partition;
	}

	@Override
	public void readFields(DataInput in) throws IOException {
		mapId = WritableUtils.readString(in);
		partition = WritableUtils.readVInt(in);
		hash = WritableUtils.readString(in);
	}

	@Override
	public void write(DataOutput out) throws IOException {
		WritableUtils.writeString(out, mapId);
		WritableUtils.writeVInt(out, partition);
		WritableUtils.writeString(out, hash);
	}
}
