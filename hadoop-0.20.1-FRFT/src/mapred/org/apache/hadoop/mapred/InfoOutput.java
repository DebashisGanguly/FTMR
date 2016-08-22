package org.apache.hadoop.mapred;

import java.io.Serializable;

public class InfoOutput implements Serializable {
	private static final long serialVersionUID = -5757678967758780690L;
	
	private String mapId;
	private String reduceTask;
	private String jobId;
	private String mapOutputFileName;

	public InfoOutput(String mapId, String reduceTask, String jobId, String fileOut) {
		this.mapId = mapId;
		this.reduceTask = reduceTask;
		this.jobId = jobId;
		this.mapOutputFileName = fileOut;
	}
	
	public String getMapId() {
		return mapId;
	}

	public String getReduceTask() {
		return reduceTask;
	}

	public String getJobId() {
		return jobId;
	}

	public String getMapOutputFileName() {
		return mapOutputFileName;
	}
}
