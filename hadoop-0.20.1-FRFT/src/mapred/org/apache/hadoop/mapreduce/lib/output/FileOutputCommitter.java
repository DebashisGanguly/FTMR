/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.mapreduce.lib.output;

import java.io.IOException;
import java.net.URI;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.OutputCommitter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.util.StringUtils;

/** An {@link OutputCommitter} that commits files specified 
 * in job output directory i.e. ${mapred.output.dir}. 
 **/
public class FileOutputCommitter extends OutputCommitter {

	private static final Log LOG = LogFactory.getLog(FileOutputCommitter.class);

	/**
	 * Temporary directory name 
	 */
	protected static final String TEMP_DIR_NAME = "_temporary";
	protected static final String SHA_DIR_NAME = "_sha";
	
	private FileSystem outputFileSystem = null;
	private Path outputPath = null;
	private Path workPath = null;

	/**
	 * Create a file output committer
	 * @param outputPath the job's output path
	 * @param context the task's context
	 * @throws IOException
	 */
	public FileOutputCommitter(Path outputPath, 
			TaskAttemptContext context) throws IOException {
		if (outputPath != null) {
			this.outputPath = outputPath;
			outputFileSystem = outputPath.getFileSystem(context.getConfiguration());
			workPath = new Path(outputPath,
					(FileOutputCommitter.TEMP_DIR_NAME + Path.SEPARATOR +
							"_" + context.getTaskAttemptID().toString()
							)).makeQualified(outputFileSystem);
		}
	}

	/**
	 * Create the temporary directory that is the root of all of the task 
	 * work directories.
	 * @param context the job's context
	 */
	public void setupJob(JobContext context) throws IOException {
		if (outputPath != null) {
			Path tmpDir = new Path(outputPath, FileOutputCommitter.TEMP_DIR_NAME);
			FileSystem fileSys = tmpDir.getFileSystem(context.getConfiguration());
			if (!fileSys.mkdirs(tmpDir)) {
				LOG.error("Mkdirs failed to create " + tmpDir.toString());
			}
			
			tmpDir = new Path(outputPath, FileOutputCommitter.SHA_DIR_NAME);
            if (!fileSys.mkdirs(tmpDir)) {
                LOG.error("Mkdirs failed to create " + tmpDir.toString());
            }
		}
	}

	/**
	 * Delete the temporary directory, including all of the work directories.
	 * @param context the job's context
	 */
	public void cleanupJob(JobContext context) throws IOException {
		if (outputPath != null) {
			Path tmpDir = new Path(outputPath, FileOutputCommitter.TEMP_DIR_NAME);
			FileSystem fileSys = tmpDir.getFileSystem(context.getConfiguration());
			if (fileSys.exists(tmpDir)) {
				fileSys.delete(tmpDir, true);
			}
		}
	}

	/**
	 * No task setup required.
	 */
	@Override
	public void setupTask(TaskAttemptContext context) throws IOException {
		// FileOutputCommitter's setupTask doesn't do anything. Because the
		// temporary task directory is created on demand when the 
		// task is writing.
	}

	/**
	 * Move the files from the work directory to the job output directory
	 * @param context the task context
	 */
	public void commitTask(TaskAttemptContext context) 
			throws IOException {
		TaskAttemptID attemptId = context.getTaskAttemptID();
		if (workPath != null) {
			context.progress();
			if (outputFileSystem.exists(workPath)) {

				// Move the task outputs to their final place
				moveTaskOutputs(context, outputFileSystem, outputPath, workPath);
				// Delete the temporary task-specific output directory
				if (!outputFileSystem.delete(workPath, true)) {
					LOG.warn("Failed to delete the temporary output directory of task: " + attemptId + " - " + workPath);
				}

				LOG.info("Saved output of task '" + attemptId + "' to " + outputPath);
			}
		}
	}

	/**
	 * Move all of the files from the work directory to the final output
	 * @param context the task context
	 * @param fs the output file system
	 * @param jobOutputDir the final output directory
	 * @param taskOutput the work path
	 * @throws IOException
	 */
	/* From the map side, this is the stack trace for this method
	        at org.apache.hadoop.mapreduce.lib.output.FileOutputCommitter.moveTaskOutputs(FileOutputCommitter.java:183)
	        at org.apache.hadoop.mapreduce.lib.output.FileOutputCommitter.moveTaskOutputs(FileOutputCommitter.java:177)
	        at org.apache.hadoop.mapreduce.lib.output.FileOutputCommitter.commitTask(FileOutputCommitter.java:121)
	        at org.apache.hadoop.mapred.Task.commit(Task.java:901)
	        at org.apache.hadoop.mapred.Task.done(Task.java:752)
	        at org.apache.hadoop.mapred.MapTask.run(MapTask.java:355)
	        at org.apache.hadoop.mapred.Child.main(Child.java:189)

	 */
	private void moveTaskOutputs(TaskAttemptContext context, FileSystem fs, Path jobOutputDir, Path taskOutput) 
			throws IOException {

		TaskAttemptID attemptId = context.getTaskAttemptID();
		context.progress();

		if (fs.isFile(taskOutput)) {
			// generate hash and saves them in file.
			Path finalOutputPath = getFinalPath(jobOutputDir, taskOutput, workPath);
			fs.setReplication(finalOutputPath, (short) 1);

			if (!fs.rename(taskOutput, finalOutputPath)) {
				if (!fs.delete(finalOutputPath, true)) {
					throw new IOException("Failed to delete earlier output of task: " + attemptId);
				}
				if (!fs.rename(taskOutput, finalOutputPath)) {
					throw new IOException("Failed to save output of task: " + attemptId);
				}
			}

//			LOG.debug("Moved of " + taskOutput + " to " + finalOutputPath);
//			if (fs.isFile(finalOutputPath)) {
//				Sha1Hash hashGen = new Sha1Hash();
//				LOG.debug("From: " + finalOutputPath.toString() + " to " + Util.getOutputFileHash(finalOutputPath));
//				hashGen.generateHashForReduce(((JobConf) context.getConfiguration()), fs, finalOutputPath, 
//				        new Path(Util.getOutputFileHash(finalOutputPath)));
//			}
		} else if(fs.getFileStatus(taskOutput).isDir()) {
			FileStatus[] paths = fs.listStatus(taskOutput);
			Path finalOutputPath = getFinalPath(jobOutputDir, taskOutput, workPath);
			fs.mkdirs(finalOutputPath);
			if (paths != null) {
				for (FileStatus path : paths) {
					moveTaskOutputs(context, fs, jobOutputDir, path.getPath());
				}
			}
		}
	}

	/**
	 * Delete the work directory
	 */
	@Override
	public void abortTask(TaskAttemptContext context) {
		try {
			if (workPath != null) { 
				context.progress();
				outputFileSystem.delete(workPath, true);
			}
		} catch (IOException ie) {
			LOG.warn("Error discarding output" + StringUtils.stringifyException(ie));
		}
	}

	/**
	 * Find the final name of a given output file, given the job output directory
	 * and the work directory.
	 * @param jobOutputDir the job's output directory
	 * @param taskOutput the specific task output file
	 * @param taskOutputPath the job's work directory
	 * @return the final path for the specific output file
	 * @throws IOException
	 */
	private Path getFinalPath(Path jobOutputDir, Path taskOutput, Path taskOutputPath) throws IOException {
		URI taskOutputUri 	= taskOutput.toUri();
		URI relativePath 	= taskOutputPath.toUri().relativize(taskOutputUri);

		if (taskOutputUri == relativePath)
			throw new IOException("Can not get the relative path: base = " + taskOutputPath + " child = " + taskOutput);
		else {
			LOG.debug("base: " + taskOutputUri + " relative: " + relativePath);
			LOG.debug("jobOutputDir: " + jobOutputDir + " taskOutput: " + taskOutput + " taskOutputPath: " + taskOutputPath);
		}

		if (relativePath.getPath().length() > 0)
			return new Path(jobOutputDir, relativePath.getPath());
		else
			return jobOutputDir;
	}

	/**
	 * Did this task write any files in the work directory?
	 * @param context the task's context
	 */
	@Override
	public boolean needsTaskCommit(TaskAttemptContext context
			) throws IOException {
		return workPath != null && outputFileSystem.exists(workPath);
	}

	/**
	 * Get the directory that the task should write results into
	 * @return the work directory
	 * @throws IOException
	 */
	public Path getWorkPath() throws IOException {
		return workPath;
	}
}
