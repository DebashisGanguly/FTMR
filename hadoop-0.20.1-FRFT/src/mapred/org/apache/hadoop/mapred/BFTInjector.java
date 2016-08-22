package org.apache.hadoop.mapred;

import java.io.IOException;
import java.util.Random;

import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

public class BFTInjector {
    public static boolean tamperingDigests(String[] digests) {
        Random randomGenerator = new Random();
        String s2 = "T" + System.currentTimeMillis() + "G";
        for(int i=0; i<digests.length; i++) {
            int randomInt = randomGenerator.nextInt(100);
            if(randomInt < 50) {
                String s1 = digests[i];
                StringBuilder sb = new StringBuilder();

                // XOR string
                for(int j=0; j<s1.length() && j<s2.length();j++) 
                    sb.append((char)(s1.charAt(j) ^ s2.charAt(j)));

                digests[i] = sb.toString();
                
                return true;
            }
        }
        
        return false;
    }


    public static void tamperingData(FileSystem fs, Path filename, int tasks) {
        FSDataOutputStream output = null;
        String trash = "TRASH" + System.currentTimeMillis();
        Random randomGenerator = new Random();
        int randomInt = randomGenerator.nextInt(100);

        try {
            if(randomInt < 50) {
                output = fs.create(filename);
                output.write(trash.getBytes());
            }
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } finally {
            if(output != null) {
                try {
                    output.close();
                } catch (IOException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
        }
    }
}
