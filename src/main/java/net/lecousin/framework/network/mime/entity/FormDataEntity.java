package net.lecousin.framework.network.mime.entity;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.async.JoinPoint;
import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.concurrent.util.AsyncProducer;
import net.lecousin.framework.concurrent.util.LinkedAsyncProducer;
import net.lecousin.framework.encoding.QuotedPrintable;
import net.lecousin.framework.encoding.charset.CharacterDecoder;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.data.ByteArray;
import net.lecousin.framework.math.RangeLong;
import net.lecousin.framework.memory.ByteArrayCache;
import net.lecousin.framework.network.mime.MimeException;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;
import net.lecousin.framework.network.mime.transfer.ContentDecoderFactory;
import net.lecousin.framework.util.AsyncCloseable;
import net.lecousin.framework.util.Pair;
import net.lecousin.framework.util.Triple;

/** form-data entity, see RFC 2388. */
public class FormDataEntity extends MultipartEntity implements AutoCloseable, AsyncCloseable<IOException> {

	public static final String MULTIPART_SUB_TYPE = "form-data";
	
	/** Constructor. */
	public FormDataEntity() {
		super(MULTIPART_SUB_TYPE);
		partFactory = new FormDataPartFactory();
	}
	
	/** Constructor. */
	public FormDataEntity(byte[] boundary) {
		super(boundary, MULTIPART_SUB_TYPE);
		partFactory = new FormDataPartFactory();
	}
	
	/** Constructor. */
	public FormDataEntity(MimeEntity parent, MimeHeaders headers) throws MimeException {
		super(parent, headers);
		partFactory = new FormDataPartFactory();
	}
	
	@Override
	public void setPartFactory(MimeEntityFactory partFactory) {
		// not allowed, ignore it
	}
	
	/** Factory to create PartField or PartFile depending on headers. */
	public static class FormDataPartFactory implements MimeEntityFactory {

		@Override
		public MimeEntity create(MimeEntity parent, MimeHeaders headers) throws MimeException {
			ParameterizedHeaderValue dispo = headers.getFirstValue(MimeHeaders.CONTENT_DISPOSITION, ParameterizedHeaderValue.class);
			if (dispo == null)
				throw new MimeException("Missing header Content-Disposition for a form-data entity, received headers:\r\n"
					+ headers.generateString(512).asString());
			if (!MULTIPART_SUB_TYPE.equals(dispo.getMainValue()))
				throw new MimeException("Invalid Content-Disposition: " + dispo.getMainValue() + ", expected is form-data");
			String fieldName = dispo.getParameter("name");
			if (fieldName == null)
				throw new MimeException("Missing parameter 'name' in Content-Disposition");
			String filename = dispo.getParameter("filename");
			ParameterizedHeaderValue type = headers.getContentType();
			if ((type == null || "text/plain".equals(type.getMainValue())) && filename == null) {
				// considered as a field
				Charset charset;
				if (type == null) charset = StandardCharsets.US_ASCII;
				else {
					String s = type.getParameter("charset");
					if (s == null) charset = StandardCharsets.US_ASCII;
					else charset = Charset.forName(s);
				}
				return new PartField((FormDataEntity)parent, headers, fieldName, charset);
			}
			// considered as a file
			return new PartFile((FormDataEntity)parent, headers, fieldName, filename);
		}
		
	}
	
	/** Part for a field. */
	public static class PartField extends MimeEntity {
		/** Constructor. */
		public PartField(FormDataEntity parent, String name, String value, Charset charset) {
			super(parent);
			this.name = name;
			this.value = value;
			this.charset = charset;
			headers.add(MimeHeaders.CONTENT_DISPOSITION, new ParameterizedHeaderValue(MULTIPART_SUB_TYPE, "name", name));
			headers.addRawValue(MimeHeaders.CONTENT_TRANSFER_ENCODING, "quoted-printable");
			headers.add(MimeHeaders.CONTENT_TYPE, new ParameterizedHeaderValue("text/plain", "charset", charset.name()));
		}
		
		/** Constructor. */
		public PartField(FormDataEntity parent, MimeHeaders headers, String name, Charset charset) {
			super(parent, headers);
			this.name = name;
			this.charset = charset;
		}
		
		protected String name;
		protected String value;
		protected Charset charset;
		
		public String getName() { return name; }
		
		public String getValue() { return value; }
		
		@Override
		public boolean canProduceBodyRange() {
			return false;
		}
		
		@Override
		public Triple<RangeLong, Long, BinaryEntity> createBodyRange(RangeLong range) {
			return null;
		}
		
		@Override
		public AsyncSupplier<Pair<Long, AsyncProducer<ByteBuffer, IOException>>, IOException> createBodyProducer() {
			ByteArray input = new ByteArray(value.getBytes(charset));
			ByteArrayCache cache = ByteArrayCache.getInstance();
			QuotedPrintable.Encoder encoder = new QuotedPrintable.Encoder();
			ByteArray.Writable output = new ByteArray.Writable(cache.get(input.remaining() + 64, true), true);
			encoder.encode(input, output, true);
			if (!input.hasRemaining() && output.hasRemaining()) {
				// size is known
				return new AsyncSupplier<>(
					new Pair<>(
						Long.valueOf(output.position()),
						new AsyncProducer.SingleData<>(ByteBuffer.wrap(output.getArray(), 0, output.position()))),
					null);
			}
			// unknown size
			return new AsyncSupplier<>(
				new Pair<Long, AsyncProducer<ByteBuffer, IOException>>(
					null,
					new LinkedAsyncProducer<>(
						new AsyncProducer.SingleData<>(ByteBuffer.wrap(output.getArray(), 0, output.position())),
						new ContinueEncodingProducer(input, cache, encoder)
					)),
				null);
		}

		private static class ContinueEncodingProducer implements AsyncProducer<ByteBuffer, IOException> {
			
			private ContinueEncodingProducer(ByteArray input, ByteArrayCache cache, QuotedPrintable.Encoder encoder) {
				this.input = input;
				this.cache = cache;
				this.encoder = encoder;
			}
			
			private ByteArray input;
			private ByteArrayCache cache;
			private QuotedPrintable.Encoder encoder;
			
			@Override
			public AsyncSupplier<ByteBuffer, IOException> produce() {
				ByteArray.Writable output = new ByteArray.Writable(cache.get(input.remaining() + 64, true), true);
				encoder.encode(input, output, true);
				if (output.position() == 0) {
					// the end
					output.free();
					return new AsyncSupplier<>(null, null);
				}
				return new AsyncSupplier<>(ByteBuffer.wrap(output.getArray(), 0, output.position()), null);
			}
			
		}
		
		@Override
		public AsyncConsumer<ByteBuffer, IOException> createConsumer(Long size) {
			return ContentDecoderFactory.createDecoder(
				CharacterDecoder.get(charset, 1024).<IOException>decodeConsumerToString(str -> value = str)
					.convert(ByteArray::fromByteBuffer),
				headers);
		}
	}
	
	/** Part for a file. */
	public static class PartFile extends BinaryEntity {
		/** Constructor. */
		public PartFile(FormDataEntity parent, String fieldName, String filename, ParameterizedHeaderValue contentType, IO.Readable content) {
			super(parent, contentType, content);
			this.fieldName = fieldName;
			this.filename = filename;
			ParameterizedHeaderValue dispo = new ParameterizedHeaderValue(MULTIPART_SUB_TYPE, "name", fieldName);
			if (filename != null)
				dispo.addParameter("filename", filename);
			headers.add(MimeHeaders.CONTENT_DISPOSITION, dispo);
		}
		
		/** Constructor. */
		public PartFile(FormDataEntity parent, MimeHeaders headers, String fieldName, String filename) {
			super(parent, headers);
			this.fieldName = fieldName;
			this.filename = filename;
		}
		
		protected String fieldName;
		protected String filename;
		
		public String getName() { return fieldName; }
		
		public String getFilename() { return filename; }
		
	}
	
	/** Append a field with a value. */
	public PartField addField(String name, String value, Charset charset) {
		PartField f = new PartField(this, name, value, charset);
		add(f);
		return f;
	}
	
	/** Append a file. */
	public PartFile addFile(String fieldName, String filename, ParameterizedHeaderValue contentType, IO.Readable content) {
		PartFile f = new PartFile(this, fieldName, filename, contentType, content);
		add(f);
		return f;
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
			try { ((PartFile)p).close(); }
			catch (Exception e) {
				throw IO.error(e);
			}
		}
	}
	
	@Override
	public IAsync<IOException> closeAsync() {
		JoinPoint<Exception> jp = new JoinPoint<>();
		for (MimeEntity p : parts) {
			if (!(p instanceof PartFile)) continue;
			jp.addToJoin(((PartFile)p).closeAsync());
		}
		jp.start();
		Async<IOException> result = new Async<>();
		jp.onDone(result, IO::error);
		return result;
	}
	
}
