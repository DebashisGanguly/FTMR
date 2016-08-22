package org.apache.hadoop.mapred;

import org.apache.hadoop.fs.Path;
import org.apache.log4j.Logger;

import java.io.*;

public class Util {
    public static Logger log = Logger.getLogger(Util.class);

    final protected static char[] hexArray = "0123456789ABCDEF".toCharArray();
    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for ( int j = 0; j < bytes.length; j++ ) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    public static void convertStreamToString(DataInput is) 
            throws IOException {
        /*
         * To convert the InputStream to String we use the BufferedReader.readLine()
         * method. We iterate until the BufferedReader return null which means
         * there's no more data to read. Each line will appended to a StringBuilder
         * and returned as String.
         */
        if (is != null) {
            StringBuilder sb = new StringBuilder();
            String line;
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new InputStreamReader((DataInputStream) is, "UTF-8"));
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            } finally {
                if(reader != null)
                    reader.close();

                ((DataInputStream) is).close();
            }

            log.debug("Stream -> String: " + sb);
        } 
    }

    public static void convertStreamToString(DataOutput out) 
            throws IOException {
        /*
         * To convert the InputStream to String we use the BufferedReader.readLine()
         * method. We iterate until the BufferedReader return null which means
         * there's no more data to read. Each line will appended to a StringBuilder
         * and returned as String.
         */
        if (out != null) {
            StringBuilder sb = new StringBuilder();
            String line;

            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader((DataInputStream) out, "UTF-8"));
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            } finally {
                ((DataInputStream) out).close();
            }

            log.debug("Stream -> String: " + sb);
        } 
    }

    public static TaskID getTaskID(TaskAttemptID tid) {
        return tid != null ? tid.getTaskID() : null;
    }

    public static Integer getId(TaskAttemptID tid) {
        return tid != null ? getId(tid.getTaskID()) : null;
    }

    public static Integer getId(TaskID tid) {
        return tid != null ? tid.getId() : null;
    }

    public static Integer getReplicaNumber(TaskAttemptID tid) {
        return tid != null ? getReplicaNumber(tid.getTaskID()) : null;
    }

    public static Integer getReplicaNumber(TaskID tid) {
        return tid != null ? tid.getReplicaNumber() : null;
    }

    public static TaskAttemptID getTaskAttemptID(Task t) {
        return t != null ? t.getTaskID() : null;
    }

    public static TaskID getTaskID(TaskInProgress tip) {
        return tip != null ? tip.getTIPId() : null;
    }


    public static synchronized String getOutputName(int partition, int num_replica) {
        return Task.getOutputName(partition, num_replica);
    }

    public static synchronized String getOutputFileHash(int partition, int num_replica) {
        return Task.getOutputFileHash(partition, num_replica);
    }

    public static synchronized String getOutputFileHash(Path output) {
        return Task.getOutputFileHash(output);
    }

    public static synchronized String getOutputFileHashDir(Path output) {
        return Task.getOutputFileHashDir(output);
    }
}
