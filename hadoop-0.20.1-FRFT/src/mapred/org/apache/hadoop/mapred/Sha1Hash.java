package org.apache.hadoop.mapred;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.zip.GZIPInputStream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DataInputBuffer;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.util.ReflectionUtils;

public class Sha1Hash extends ShaAbstractHash {

	private static final Log LOG = LogFactory.getLog(Sha1Hash.class.getName());

	public byte[] generateHashForReduce(FileSystem rfs, Path pi) {
		byte[] digest = null;
		MessageDigest md = newInstance();
		try {
			FSDataInputStream input = rfs.open(pi);

			byte[] buffer = new byte[10];

			while(input.read(buffer, 0, 10) > 0) {
				md.update(buffer);    
			}

			digest = hashIt(md);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return digest;
	}

	//    /**
	//     * Generate the digest from the reduce output
	//     * @param className
	//     * @param conf
	//     * @param rfs
	//     * @param inFile
	//     * @param outFile
	//     * @deprecated
	//     * @return
	//     */
	//    public String generateHashForReduce(FileSystem rfs, Path inFile, Path outFile) {
	//        LOG.debug("Reading file: " + inFile.toString());
	//        LOG.debug("Writing to file: " + outFile.toString());
	//
	//        Path pi = inFile;
	//        FSDataOutputStream output = null;
	//        String digest = null;
	//        try {
	//            digest = generateHashForReduce(rfs, pi);
	//
	//            LOG.debug("Digest for " + inFile + " is " + digest + "\nsaved at " + outFile);
	//
	//            // save hash in file
	//            output = rfs.create(outFile, (short) 1);
	//            output.write(digest.getBytes(), 0, digest.length());
	//            output.write("\n".getBytes());
	//            output.flush();
	//        } catch (IOException e) {
	//            e.printStackTrace();
	//            return null;
	//        } finally {
	//            if(output != null) {
	//                try {
	//                    output.close();
	//                } catch (IOException e) {
	//                    // TODO Auto-generated catch block
	//                    e.printStackTrace();
	//                    return null;
	//                }
	//            }
	//        }
	//
	//        return digest;
	//    }


	public byte[] generateHash(FileSystem rfs, Path filename, int offset, int mapOutputLength) {
		LOG.debug("Opening file2: " + filename);
		LOG.debug("offset: " + offset + " length: " + mapOutputLength + " offset: " + offset);

		if(mapOutputLength == 0)
			return new byte[0];

		byte[] digest = null;
		newInstance();

		try {
			digest = generateHash(rfs.open(filename));
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return digest;
	}

	public byte[] generateHash(InputStream bis, int offset, int mapOutputLength) {
		if(bis == null)
			LOG.debug("DATA IS NULL. - " + mapOutputLength);

		if(mapOutputLength <= 0)
			return new byte[0];

		MessageDigest md1 = null;
		try {
			md1 = newInstance();
			
			int size;
			byte[] buffer;
			while (mapOutputLength > 0) {
				// the case that the bytes read is small the the default size.
				// We don't want that the message digest contains trash.
				size = mapOutputLength < (60 * 1024) ? mapOutputLength : (60*1024);

				if(size == 0)
					break;

				buffer = new byte[size];
				if(bis.read(buffer, offset, size) < 0)
					break;

				md1.update(buffer);
				mapOutputLength -= size;

			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			if(bis!= null)
				try {
					bis.close();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
		}

		return hashIt(md1);
	}

	private byte[] generateHash(InputStream bis) {
		if(bis == null)
			LOG.debug("DATA IS NULL.");

		MessageDigest md = newInstance();
		try {
			LOG.debug("Start to read");
			byte[] buffer = new byte[4096];
			int n;
			while ((n = bis.read(buffer)) >= 0) {
				LOG.debug("Read... " + n);
				md.update(buffer);
			}
			LOG.debug("ENDOF Start to read");
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			if(bis!= null)
				try {
					bis.close();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
		}

		return hashIt(md);
	}

	/**
	 * @deprecated
	 * @param data
	 */
	//    public void addDataToHash(byte[] data) {
	//        ByteArrayInputStream bis = null;
	//
	//        if(data == null || data.length == 0)
	//            return;
	//
	//        //        LOG.debug("DATA IS - " + data.length + " - " + mapOutputLength);
	//        MessageDigest md1 = null;
	//        int mapOutputLength = data.length;
	//        try {
	//            bis = new ByteArrayInputStream(data);
	//            try {
	//                md1 = MessageDigest.getInstance("SHA-1");
	//            } catch (NoSuchAlgorithmException e) {
	//                // TODO Auto-generated catch block
	//                e.printStackTrace();
	//            }
	//            int size;
	//            byte[] buffer;
	//
	//            while (mapOutputLength > 0) {
	//                // the case that the bytes read is small the the default size.
	//                // We don't want that the message digest contains trash.
	//                size = mapOutputLength < (60 * 1024) ? mapOutputLength : (60*1024);
	//
	//                if(size == 0)
	//                    break;
	//
	//                buffer = new byte[size];
	//                if(bis.read(buffer, 0, size) < 0)
	//                    break;
	//
	//                md1.update(buffer);
	//                mapOutputLength -= size;
	//
	//            }
	//        } finally {
	//            if(bis!= null)
	//                try {
	//                    bis.close();
	//                } catch (IOException e) {
	//                    // TODO Auto-generated catch block
	//                    e.printStackTrace();
	//                }
	//        }
	//    }

	/**
	 * Hash it!
	 * @param md
	 * @param buf
	 */
	byte[] hashIt(MessageDigest md) {
		// hash it
		byte[] hash = super.hashIt(md);
		if (LOG.isDebugEnabled()) {
			String digest = convertHashToString(hash);
			LOG.debug("Generated digest: " + digest);
		}

		return hash;
	}

	private MessageDigest newInstance() {
		return super.newInstance(Digest.SHA1);
	}

	private class TextRecordInputStream extends InputStream {
		SequenceFile.Reader r;
		WritableComparable key;
		Writable val;

		DataInputBuffer inbuf;
		DataOutputBuffer outbuf;

		public TextRecordInputStream(FileSystem rfs, JobConf conf, FileStatus f) throws IOException {
			r = new SequenceFile.Reader(rfs, f.getPath(), conf);
			key = ReflectionUtils.newInstance(r.getKeyClass().asSubclass(WritableComparable.class), conf);
			val = ReflectionUtils.newInstance(r.getValueClass().asSubclass(Writable.class), conf);
			inbuf = new DataInputBuffer();
			outbuf = new DataOutputBuffer();
		}

		public int read() throws IOException {
			int ret;
			if (null == inbuf || -1 == (ret = inbuf.read())) {
				if (!r.next(key, val)) {
					return -1;
				}
				byte[] tmp = key.toString().getBytes();
				outbuf.write(tmp, 0, tmp.length);
				outbuf.write('\t');
				tmp = val.toString().getBytes();
				outbuf.write(tmp, 0, tmp.length);
				outbuf.write('\n');
				inbuf.reset(outbuf.getData(), outbuf.getLength());
				outbuf.reset();
				ret = inbuf.read();
			}
			return ret;
		}
	}


	byte[] text(FileSystem rfs, JobConf conf, String srcf) throws IOException {
		return generateHash(forMagic(new Path(srcf), conf, rfs));
	}

	private InputStream forMagic(Path p, JobConf conf, FileSystem srcFs) throws IOException {
		FSDataInputStream i = srcFs.open(p);
		switch(i.readShort()) {
		case 0x1f8b: // RFC 1952
			LOG.debug("Reading GZIPInputStream");
			i.seek(0);
			return new GZIPInputStream(i);
		case 0x5345: // 'S' 'E'
			if (i.readByte() == 'Q') {
				LOG.debug("Reading TextRecordInputStream");
				i.close();
				return new TextRecordInputStream(srcFs, conf, srcFs.getFileStatus(p));
			}
			break;
		}

		LOG.debug("Reading FSDataInputStream");
		i.seek(0);
		return i;
	}
}

