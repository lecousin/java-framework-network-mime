package net.lecousin.framework.network.mime.entity;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;
import java.util.function.UnaryOperator;

import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.async.JoinPoint;
import net.lecousin.framework.concurrent.tasks.drives.RemoveFileTask;
import net.lecousin.framework.io.FileIO;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.IOUtil;
import net.lecousin.framework.io.TemporaryFiles;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.buffering.ByteBuffersIO;
import net.lecousin.framework.io.buffering.IOInMemoryOrFile;
import net.lecousin.framework.io.encoding.QuotedPrintable;
import net.lecousin.framework.network.mime.MimeHeader;
import net.lecousin.framework.network.mime.MimeMessage;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;
import net.lecousin.framework.network.mime.transfer.encoding.ContentDecoder;
import net.lecousin.framework.network.mime.transfer.encoding.ContentDecoderFactory;
import net.lecousin.framework.network.mime.transfer.encoding.IdentityDecoder;
import net.lecousin.framework.util.AsyncCloseable;
import net.lecousin.framework.util.Pair;
import net.lecousin.framework.util.UnprotectedStringBuffer;

/** form-data entity, see RFC 2388. */
public class FormDataEntity extends MultipartEntity implements Closeable, AsyncCloseable<IOException> {

	public static final String MULTIPART_SUB_TYPE = "form-data";
	
	/** Constructor. */
	public FormDataEntity() {
		super(MULTIPART_SUB_TYPE);
	}
	
	/** Constructor. */
	public FormDataEntity(byte[] boundary) {
		super(boundary, MULTIPART_SUB_TYPE);
	}
	
	/** Part for a field. */
	public static class PartField extends MimeEntity {
		/** Constructor. */
		public PartField(String name, String value, Charset charset) {
			this.name = name;
			this.value = value;
			this.charset = charset;
			addHeader(CONTENT_DISPOSITION, new ParameterizedHeaderValue(MULTIPART_SUB_TYPE, "name", name));
			addHeaderRaw(CONTENT_TRANSFER_ENCODING, "quoted-printable");
			addHeader(CONTENT_TYPE, new ParameterizedHeaderValue("text/plain", "charset", charset.name()));
		}
		
		protected String name;
		protected String value;
		protected Charset charset;
		
		public String getName() { return name; }
		
		public String getValue() { return value; }
		
		@Override
		public IO.Readable getBodyToSend() {
			ByteBuffer content = QuotedPrintable.encode(value, charset);
			return new ByteArrayIO(content.array(), content.remaining(), "form-data field content");
		}
	}
	
	/** Part for a file. */
	public static class PartFile extends MimeEntity {
		/** Constructor. */
		public PartFile(String fieldName, String filename, ParameterizedHeaderValue contentType, IO.Readable content) {
			this.fieldName = fieldName;
			this.filename = filename;
			setBodyToSend(content);
			addHeader(CONTENT_TYPE, contentType);
			ParameterizedHeaderValue dispo = new ParameterizedHeaderValue(MULTIPART_SUB_TYPE, "name", fieldName);
			if (filename != null)
				dispo.addParameter("filename", filename);
			addHeader(CONTENT_DISPOSITION, dispo);
		}
		
		protected String fieldName;
		protected String filename;
		
		public String getName() { return fieldName; }
		
		public String getFilename() { return filename; }
		
	}
	
	/** Append a field with a value. */
	public PartField addField(String name, String value, Charset charset) {
		PartField f = new PartField(name, value, charset);
		add(f);
		return f;
	}
	
	/** Append a file. */
	public PartFile addFile(String fieldName, String filename, ParameterizedHeaderValue contentType, IO.Readable content) {
		PartFile f = new PartFile(fieldName, filename, contentType, content);
		add(f);
		return f;
	}
	
	/** Return the fields contained in the form-data. */
	public List<Pair<String, String>> getFields() {
		LinkedList<Pair<String, String>> list = new LinkedList<>();
		for (MimeMessage p : parts)
			if (p instanceof PartField)
				list.add(new Pair<>(((PartField)p).getName(), ((PartField)p).getValue()));
		return list;
	}
	
	/** Return the value of the given field. */
	public String getFieldValue(String name) {
		for (MimeMessage p : parts)
			if ((p instanceof PartField) && ((PartField)p).getName().equals(name))
				return ((PartField)p).getValue();
		return null;
	}
	
	/** Return the file corresponding to the field of the given name. */
	public PartFile getFile(String name) {
		for (MimeMessage p : parts)
			if ((p instanceof PartFile) && ((PartFile)p).getName().equals(name))
				return (PartFile)p;
		return null;
	}
	
	@Override
	public void close() throws IOException {
		for (MimeMessage p : parts) {
			if (!(p instanceof PartFile)) continue;
			try { ((PartFile)p).getReadableStream().close(); }
			catch (Exception e) {
				throw IO.error(e);
			}
		}
	}
	
	@Override
	public IAsync<IOException> closeAsync() {
		JoinPoint<Exception> jp = new JoinPoint<>();
		for (MimeMessage p : parts) {
			if (!(p instanceof PartFile)) continue;
			jp.addToJoin(((PartFile)p).getReadableStream().closeAsync());
		}
		jp.start();
		Async<IOException> result = new Async<>();
		jp.onDone(result, IO::error);
		return result;
	}
	
	@Override
	@SuppressWarnings("squid:S2095") // IOs are closed, but asynchronously
	protected AsyncSupplier<MimeMessage, IOException> createPart(List<MimeHeader> headers, IOInMemoryOrFile body, boolean asReceived) {
		try {
			ParameterizedHeaderValue dispo = null;
			for (MimeHeader h : headers)
				if ("content-disposition".equals(h.getNameLowerCase())) {
					dispo = h.getValue(ParameterizedHeaderValue.class);
					break;
				}
			if (dispo == null)
				throw new IOException("Missing header Content-Disposition for a form-data entity");
			if (!MULTIPART_SUB_TYPE.equals(dispo.getMainValue()))
				throw new IOException("Invalid Content-Disposition: " + dispo.getMainValue() + ", expected is form-data");
			String fieldName = dispo.getParameter("name");
			if (fieldName == null)
				throw new IOException("Missing parameter 'name' in Content-Disposition");
			String filename = dispo.getParameter("filename");
			ParameterizedHeaderValue type = null;
			for (MimeHeader h : headers)
				if ("content-type".equals(h.getNameLowerCase())) {
					type = h.getValue(ParameterizedHeaderValue.class);
					break;
				}
			if ((type == null || "text/plain".equals(type.getMainValue())) && body.getSizeSync() < 64 * 1024 && filename == null) {
				// considered as a field
				Charset charset;
				if (type == null) charset = StandardCharsets.US_ASCII;
				else {
					String s = type.getParameter("charset");
					if (s == null) charset = StandardCharsets.US_ASCII;
					else charset = Charset.forName(s);
				}

				ByteBuffersIO out = new ByteBuffersIO(false, "form-data field value", Task.PRIORITY_NORMAL);
				ContentDecoder decoder = ContentDecoderFactory.createDecoder(out, new MimeMessage(headers));
				AsyncSupplier<MimeMessage, IOException> result = new AsyncSupplier<>();
				if (decoder instanceof IdentityDecoder) {
					readField(fieldName, body, charset, result); // no encoding
					out.closeAsync();
					return result;
				}
				decodeField(fieldName, decoder, body, out, charset, result);
				return result;
			}
			// considered as a file
			AsyncSupplier<FileIO.ReadWrite, IOException> tmp = TemporaryFiles.get().createAndOpenFileAsync("formData", "file");
			AsyncSupplier<MimeMessage, IOException> result = new AsyncSupplier<>();
			ParameterizedHeaderValue typ = type;
			tmp.thenDoOrStart(io -> {
				ContentDecoder decoder = ContentDecoderFactory.createDecoder(io, new MimeMessage(headers));
				if (decoder instanceof IdentityDecoder) {
					io.closeAsync().onDone(() -> new RemoveFileTask(io.getFile(), Task.PRIORITY_LOW).start());
					result.unblockSuccess(new PartFile(fieldName, filename, typ, body));
				} else {
					decodeFile(fieldName, filename, typ, body, decoder, io, result);
					io.addCloseListener(() -> new RemoveFileTask(io.getFile(), Task.PRIORITY_LOW).start());
				}
			}, "Decoding form-data file", Task.PRIORITY_NORMAL, result);
			return result;
		} catch (Exception e) {
			return new AsyncSupplier<>(null, IO.error(e));
		}
	}
	
	private static void readField(String fieldName, IO.Readable content, Charset charset, AsyncSupplier<MimeMessage, IOException> result) {
		AsyncSupplier<UnprotectedStringBuffer, IOException> read = IOUtil.readFullyAsString(content, charset, Task.PRIORITY_NORMAL);
		read.onDone(result, () -> new PartField(fieldName, read.getResult().asString(), charset));
	}
	
	private static final String DECODE_ERROR_MESSAGE = "Error decoding value of form-data field ";
	
	private static void decodeField(
		String fieldName, ContentDecoder decoder, IOInMemoryOrFile encoded, ByteBuffersIO decoded,
		Charset charset, AsyncSupplier<MimeMessage, IOException> result
	) {
		ByteBuffer buf = ByteBuffer.allocate((int)encoded.getSizeSync());
		AsyncSupplier<Integer, IOException> read = encoded.readFullyAsync(buf);
		UnaryOperator<IOException> errorConverter = e -> new IOException(DECODE_ERROR_MESSAGE + fieldName, e);
		read.onDone(() -> {
			buf.flip();
			IAsync<IOException> decode = decoder.decode(buf);
			decode.onDone(() -> {
				IAsync<IOException> end = decoder.endOfData();
				end.onDone(() -> {
					decoded.seekSync(SeekType.FROM_BEGINNING, 0);
					readField(fieldName, decoded, charset, result);
				}, result, errorConverter);
			}, result, errorConverter);
		}, result, errorConverter);
	}
	
	private static void decodeFile(
		String fieldName, String filename, ParameterizedHeaderValue contentType, IO.Readable encoded,
		ContentDecoder decoder, FileIO.ReadWrite file, AsyncSupplier<MimeMessage, IOException> result
	) {
		ByteBuffer buf = ByteBuffer.allocate(65536);
		AsyncSupplier<Integer, IOException> read = encoded.readFullyAsync(buf);
		UnaryOperator<IOException> errorConverter = e -> {
			file.closeAsync();
			return new IOException(DECODE_ERROR_MESSAGE + fieldName, e);
		};
		read.thenStart(new Task.Cpu.FromRunnable("Reading form-data file", Task.PRIORITY_NORMAL, () -> {
			buf.flip();
			IAsync<IOException> decode = decoder.decode(buf);
			decode.onDone(() -> {
				if (read.getResult().intValue() < 65536) {
					IAsync<IOException> end = decoder.endOfData();
					end.onDone(() -> {
						AsyncSupplier<Long, IOException> seek = file.seekAsync(SeekType.FROM_BEGINNING, 0);
						seek.onDone(() -> {
							result.unblockSuccess(new PartFile(fieldName, filename, contentType, file));
						}, result, errorConverter);
					}, result, errorConverter);
					return;
				}
				decodeFile(fieldName, filename, contentType, encoded, decoder, file, result);
			}, result, errorConverter);
		}), result, errorConverter);
	}
	
}
