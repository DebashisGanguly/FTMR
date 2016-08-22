package org.apache.hadoop.mapred;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

import org.apache.commons.codec.binary.Base64;
import org.apache.hadoop.io.DataInputBuffer;
import org.apache.hadoop.io.IntWritable;

public class EncodeDecode {

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] barr = new byte[1024];
        while(true) {
                int r = in.read(barr);
                if(r <= 0) {
                        break;
                }
                out.write(barr, 0, r);
        }
    }

    private static byte[] loadFile(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                copy(in, buffer);
                return buffer.toByteArray();
        } finally {
                in.close();
        }
    }

    // java -cp ../../lib/commons-codec-1.3.jar org.apache.hadoop.mapred.EncodeDecode /tmp/backup/dir/hadoop-hadoop/tmp/dir/hadoop-hadoop/mapred/local/taskTracker/jobcache/job_201008092237_0001/attempt_201008092237_0001_m_000000_0_0_m_0/output/file0_0.out

    public static void main(String[] args) throws Exception {
    	
    	//File file = new File("/bin/ls");
    	System.out.println(args[0]);
    	byte[] bytes = loadFile(new File(args[0]));
    	System.out.println(bytes.length + " " + Base64.isArrayByteBase64(bytes));
//        String p = "/tmp/backup/dir/hadoop-hadoop/tmp/dir/hadoop-hadoop/mapred/local/taskTracker/jobcache/job_201008092237_0001/attempt_201008092237_0001_m_000000_0_0_m_0/output/file0_0.out";
//        byte[] bytes = loadFile(new File(p));
//        //all chars in encoded are guaranteed to be 7-bit ASCII
//        byte[] encoded = Base64.encodeBase64(bytes);
//
//        String printMe = new String(encoded, "ASCII");
//        System.out.println(printMe);

        //byte[] decoded = Base64.decodeBase64(encoded);
    	byte[] decoded = Base64.decodeBase64(bytes);
    	DataInputBuffer dib = new DataInputBuffer();
    	dib.reset(decoded, decoded.length);
    	IntWritable iw = new IntWritable();
    	iw.readFields(dib);
    	System.out.println(Arrays.toString(decoded));
    	System.out.println(iw.get());
        // check
//        for (int i = 0; i < bytes.length; i++) {
//                assert bytes[i] == decoded[i];
//        }
    }

}

