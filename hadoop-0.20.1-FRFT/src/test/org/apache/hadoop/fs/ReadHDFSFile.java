package org.apache.hadoop.fs;
import java.io.IOException;
import java.net.URI;

import org.apache.hadoop.conf.Configuration;

public class ReadHDFSFile {

	public static void main(String[] args) throws IOException { 
		FileSystem hdfs = FileSystem.get(
				URI.create("/home/xeon/attempt_201010131928_0001_m_000000_0_m_0/output/file_0.index"), 
				new Configuration());

		Path path = new Path("/home/xeon/attempt_201010131928_0001_m_000000_0_m_0/output/file_0.index");

		//writing FSDataOutputStream dos = hdfs.create(path); dos.writeUTF("Hello World"); dos.close();

		//reading 
		FSDataInputStream dis = hdfs.open(path); 
		System.out.println(dis.readUTF()); 
		dis.close();

		hdfs.close(); 
	}
}
