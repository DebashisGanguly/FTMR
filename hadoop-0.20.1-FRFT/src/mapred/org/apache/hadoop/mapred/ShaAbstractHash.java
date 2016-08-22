package org.apache.hadoop.mapred;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public abstract class ShaAbstractHash {
	MessageDigest newInstance(Digest type) {
		MessageDigest md = null;
		try {
			md = MessageDigest.getInstance(type.toString());
		} catch (NoSuchAlgorithmException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return md;
	}

	/**
	 * Digest enum type
	 * @author xeon
	 *
	 */
	public enum Digest {
		SHA1, SHA256;

		@Override public String toString() {
			//only capitalize the first letter
			String digest = "";
			switch (this) {
			case SHA1:
				digest = "SHA-1";
				break;
			case SHA256:
				digest = "SHA-256";
				break;
			default:
				digest = null;
				break;
			}
			return digest;
		}
	} 

	/**
	 * Hash it!
	 * @param md MessageDigest
	 */
	byte[] hashIt(MessageDigest md) {
		// hash it
		return md.digest();
	}


	/**
	 * Convert a digest into HEX
	 * @param digest
	 * @return
	 */
	public static String convertHashToString(byte[] digest) {

		StringBuffer buf = new StringBuffer();

		// convert hash to HEX
		for (int i = 0; i < digest.length; i++) {
			int halfbyte = (digest[i] >>> 4) & 0x0F;
			int two_halfs = 0;
			do {
				if ((0 <= halfbyte) && (halfbyte <= 9))
					buf.append((char) ('0' + halfbyte));
				else
					buf.append((char) ('a' + (halfbyte - 10)));

				halfbyte = digest[i] & 0x0F;
			} while(two_halfs++ < 1);
		}

		return buf.toString();
	}

	public static String convertHash256ToString(byte[] digest) {
		//convert the byte to hex format method 2
		StringBuffer hexString = new StringBuffer();
		for (int i=0;i<digest.length;i++) {
			hexString.append(Integer.toHexString(0xFF & digest[i]));
		}
		
		return hexString.toString();
	}


	/**
	 * Generate digests (SHA-1, or SHA-256) for the map tasks
	 * @param rfs
	 * @param filename
	 * @param offset
	 * @param mapOutputLength
	 * @return
	 */
	public abstract byte[] generateHash(FileSystem rfs, Path filename, int offset, int mapOutputLength);

	/**
	 * Generate digests (SHA-1, or SHA-256) for the reduce tasks
	 * @param bis
	 * @param offset
	 * @param mapOutputLength
	 * @return
	 */
	public abstract byte[] generateHash(InputStream bis, int offset, int mapOutputLength);
}

