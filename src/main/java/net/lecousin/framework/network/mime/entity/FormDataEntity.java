package net.lecousin.framework.network.mime.entity;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.synch.AsyncWork;
import net.lecousin.framework.concurrent.synch.ISynchronizationPoint;
import net.lecousin.framework.concurrent.synch.JoinPoint;
import net.lecousin.framework.concurrent.synch.SynchronizationPoint;
import net.lecousin.framework.concurrent.tasks.drives.RemoveFileTask;
import net.lecousin.framework.io.FileIO;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.IOUtil;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.buffering.ByteBuffersIO;
import net.lecousin.framework.io.buffering.IOInMemoryOrFile;
import net.lecousin.framework.io.encoding.QuotedPrintable;
import net.lecousin.framework.network.mime.MIME;
import net.lecousin.framework.network.mime.MIMEUtil;
import net.lecousin.framework.network.mime.transfer.TransferEncodingFactory;
import net.lecousin.framework.network.mime.transfer.encoding.ContentDecoder;
import net.lecousin.framework.network.mime.transfer.encoding.IdentityDecoder;
import net.lecousin.framework.util.AsyncCloseable;
import net.lecousin.framework.util.Pair;
import net.lecousin.framework.util.UnprotectedStringBuffer;

/** form-data entity, see RFC 2388. */
public class FormDataEntity extends MultipartEntity implements Closeable, AsyncCloseable<IOException> {

	/** Constructor. */
	public FormDataEntity() {
		super("form-data");
	}
	
	/** Constructor. */
	public FormDataEntity(byte[] boundary) {
		super(boundary, "form-data");
	}
	
	/** Part for a field. */
	public static class PartField implements MimeEntity {
		/** Constructor. */
		public PartField(String name, String value, Charset charset) {
			this.name = name;
			this.value = value;
			this.charset = charset;
		}
		
		protected String name;
		protected String value;
		protected Charset charset;
		
		public String getName() { return name; }
		
		public String getValue() { return value; }
		
		@Override
		public String getContentType() {
			return null;
		}
		
		@Override
		public List<Pair<String, String>> getAdditionalHeaders() {
			ArrayList<Pair<String, String>> headers = new ArrayList<>(2);
			headers.add(new Pair<>("Content-Disposition", "form-data; name=" + MIMEUtil.encodeHeaderParameterValue(name, charset)));
			headers.add(new Pair<>("Content-Transfer-Encoding", "quoted-printable"));
			headers.add(new Pair<>("Content-Type", "text/plain; charset=" + charset.name()));
			return headers;
		}
		
		@Override
		public IO.Readable getReadableStream() {
			ByteBuffer content = QuotedPrintable.encode(value, charset);
			return new ByteArrayIO(content.array(), content.remaining(), "form-data field content");
		}
	}
	
	/** Part for a file. */
	public static class PartFile implements MimeEntity {
		/** Constructor. */
		public PartFile(String fieldName, String filename, String contentType, IO.Readable content, String... headers) {
			this.fieldName = fieldName;
			this.filename = filename;
			this.contentType = contentType;
			this.content = content;
			for (int i = 0; i < headers.length - 1; i += 2)
				this.headers.add(new Pair<>(headers[i], headers[i + 1]));
		}
		
		protected String fieldName;
		protected String filename;
		protected String contentType;
		protected IO.Readable content;
		protected List<Pair<String, String>> headers = new LinkedList<>();
		
		public String getName() { return fieldName; }
		
		public String getFilename() { return filename; }
		
		@Override
		public String getContentType() { return contentType; }
		
		@Override
		public List<Pair<String, String>> getAdditionalHeaders() {
			ArrayList<Pair<String, String>> headers = new ArrayList<>(1);
			StringBuilder dispo = new StringBuilder(128);
			dispo.append("form-data; name=");
			dispo.append(MIMEUtil.encodeUTF8HeaderParameterValue(fieldName));
			if (filename != null) {
				dispo.append("; filename=");
				dispo.append(MIMEUtil.encodeUTF8HeaderParameterValue(filename));
			}
			headers.add(new Pair<>("Content-Disposition", dispo.toString()));
			headers.addAll(this.headers);
			return headers;
		}
		
		@Override
		public IO.Readable getReadableStream() {
			return content;
		}
	}
	
	/** Append a field with a value. */
	public void addField(String name, String value, Charset charset) {
		add(new PartField(name, value, charset));
	}
	
	/** Append a file. */
	public void addFile(String fieldName, String filename, String contentType, IO.Readable content, String... headers) {
		add(new PartFile(fieldName, filename, contentType, content, headers));
	}
	
	/** Return the fields contained in the form-data. */
	public List<Pair<String, String>> getFields() {
		LinkedList<Pair<String, String>> list = new LinkedList<>();
		for (MimeEntity p : parts)
			if (p instanceof PartField)
				list.add(new Pair<>(((PartField)p).getName(), ((PartField)p).getValue()));
		return list;
	}
	
	/** Return the value of the given field. */
	public String getFieldValue(String name) {
		for (MimeEntity p : parts)
			if ((p instanceof PartField) && ((PartField)p).getName().equals(name))
				return ((PartField)p).getValue();
		return null;
	}
	
	/** Return the file corresponding to the field of the given name. */
	public PartFile getFile(String name) {
		for (MimeEntity p : parts)
			if ((p instanceof PartFile) && ((PartFile)p).getName().equals(name))
				return (PartFile)p;
		return null;
	}
	
	@Override
	public void close() throws IOException {
		for (MimeEntity p : parts) {
			if (!(p instanceof PartFile)) continue;
			try { ((PartFile)p).getReadableStream().close(); }
			catch (Exception e) {
				throw IO.error(e);
			}
		}
	}
	
	@Override
	public ISynchronizationPoint<IOException> closeAsync() {
		JoinPoint<Exception> jp = new JoinPoint<>();
		for (MimeEntity p : parts) {
			if (!(p instanceof PartFile)) continue;
			jp.addToJoin(((PartFile)p).getReadableStream().closeAsync());
		}
		jp.start();
		SynchronizationPoint<IOException> result = new SynchronizationPoint<>();
		jp.listenInline(() -> {
			if (jp.hasError())
				result.error(IO.error(jp.getError()));
			else
				result.unblock();
		});
		return result;
	}
	
	@SuppressWarnings("resource")
	@Override
	protected AsyncWork<MimeEntity, IOException> createPart(MIME headers, IOInMemoryOrFile body) {
		try {
			Pair<String, Map<String, String>> dispo = headers.parseParameterizedHeaderSingleValue("Content-Disposition");
			if (dispo == null)
				throw new IOException("Missing header Content-Disposition for a form-data entity");
			if (!"form-data".equals(dispo.getValue1()))
				throw new IOException("Invalid Content-Disposition: " + dispo.getValue1() + ", expected is form-data");
			String fieldName = dispo.getValue2().get("name");
			if (fieldName == null)
				throw new IOException("Missing parameter 'name' in Content-Disposition");
			String filename = dispo.getValue2().get("filename");
			Pair<String, Map<String, String>> type = headers.parseContentType();
			if ((type == null || "text/plain".equals(type.getValue1())) && body.getSizeSync() < 64 * 1024 && filename == null) {
				// considered as a field
				Charset charset;
				if (type == null) charset = StandardCharsets.US_ASCII;
				else {
					String s = type.getValue2().get("charset");
					if (s == null) charset = StandardCharsets.US_ASCII;
					else charset = Charset.forName(s);
				}

				ByteBuffersIO out = new ByteBuffersIO(false, "form-data field value", Task.PRIORITY_NORMAL);
				ContentDecoder decoder = new IdentityDecoder(out);
				decoder = TransferEncodingFactory.createDecoder(decoder, headers);
				AsyncWork<MimeEntity, IOException> result = new AsyncWork<>();
				if (decoder instanceof IdentityDecoder) {
					readField(fieldName, body, charset, result); // no encoding
					out.closeAsync();
					return result;
				}
				decodeField(fieldName, decoder, body, out, charset, result);
				return result;
			}
			// considered as a file
			File tmp = File.createTempFile("formData", "file");
			tmp.deleteOnExit();
			FileIO.ReadWrite io = new FileIO.ReadWrite(tmp, Task.PRIORITY_NORMAL);
			ContentDecoder decoder = new IdentityDecoder(io);
			decoder = TransferEncodingFactory.createDecoder(decoder, headers);
			if (decoder instanceof IdentityDecoder) {
				io.closeAsync().listenInline(() -> {
					new RemoveFileTask(tmp, Task.PRIORITY_LOW).start();
				});
				return new AsyncWork<>(new PartFile(fieldName, filename, headers.getContentType(), body), null);
			}
			AsyncWork<MimeEntity, IOException> result = new AsyncWork<>();
			decodeFile(fieldName, filename, headers.getContentType(), body, decoder, io, result);
			io.addCloseListener(() -> {
				new RemoveFileTask(tmp, Task.PRIORITY_LOW).start();
			});
			return result;
		} catch (IOException e) {
			return new AsyncWork<>(null, e);
		}
	}
	
	private static void readField(String fieldName, IO.Readable content, Charset charset, AsyncWork<MimeEntity, IOException> result) {
		AsyncWork<UnprotectedStringBuffer, IOException> read = IOUtil.readFullyAsString(content, charset, Task.PRIORITY_NORMAL).getOutput();
		read.listenInline(() -> {
			if (read.hasError()) {
				result.error(read.getError());
				return;
			}
			result.unblockSuccess(new PartField(fieldName, read.getResult().asString(), charset));
		});
	}
	
	private static void decodeField(
		String fieldName, ContentDecoder decoder, IOInMemoryOrFile encoded, ByteBuffersIO decoded,
		Charset charset, AsyncWork<MimeEntity, IOException> result
	) {
		ByteBuffer buf = ByteBuffer.allocate((int)encoded.getSizeSync());
		AsyncWork<Integer, IOException> read = encoded.readFullyAsync(buf);
		read.listenInline(() -> {
			if (read.hasError()) {
				result.error(new IOException("Error reading value of form-data field " + fieldName, read.getError()));
				return;
			}
			buf.flip();
			ISynchronizationPoint<IOException> decode = decoder.decode(buf);
			decode.listenInline(() -> {
				if (decode.hasError()) {
					result.error(new IOException("Error decoding value of form-data field " + fieldName, decode.getError()));
					return;
				}
				ISynchronizationPoint<IOException> end = decoder.endOfData();
				end.listenInline(() -> {
					if (end.hasError()) {
						result.error(new IOException("Error decoding value of form-data field " + fieldName, end.getError()));
						return;
					}
					decoded.seekSync(SeekType.FROM_BEGINNING, 0);
					readField(fieldName, decoded, charset, result);
				});
			});
		});
	}
	
	private static void decodeFile(
		String fieldName, String filename, String contentType, IO.Readable encoded,
		ContentDecoder decoder, FileIO.ReadWrite file, AsyncWork<MimeEntity, IOException> result
	) {
		ByteBuffer buf = ByteBuffer.allocate(65536);
		AsyncWork<Integer, IOException> read = encoded.readFullyAsync(buf);
		read.listenAsync(new Task.Cpu.FromRunnable("Reading form-data file", Task.PRIORITY_NORMAL, () -> {
			if (read.hasError()) {
				result.error(new IOException("Error reading value of form-data field " + fieldName, read.getError()));
				file.closeAsync();
				return;
			}
			buf.flip();
			ISynchronizationPoint<IOException> decode = decoder.decode(buf);
			decode.listenInline(() -> {
				if (decode.hasError()) {
					result.error(new IOException("Error decoding value of form-data field " + fieldName, decode.getError()));
					file.closeAsync();
					return;
				}
				if (read.getResult().intValue() < 65536) {
					ISynchronizationPoint<IOException> end = decoder.endOfData();
					end.listenInline(() -> {
						if (end.hasError()) {
							result.error(new IOException("Error decoding value of form-data field "
								+ fieldName, end.getError()));
							file.closeAsync();
							return;
						}
						AsyncWork<Long, IOException> seek = file.seekAsync(SeekType.FROM_BEGINNING, 0);
						seek.listenInline(() -> {
							if (seek.hasError()) {
								result.error(seek.getError());
								file.closeAsync();
								return;
							}
							result.unblockSuccess(new PartFile(fieldName, filename, contentType, file));
						});
					});
					return;
				}
				decodeFile(fieldName, filename, contentType, encoded, decoder, file, result);
			});
		}), true);
	}
	
}
