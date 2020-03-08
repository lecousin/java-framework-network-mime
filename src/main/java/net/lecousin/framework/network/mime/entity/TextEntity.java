package net.lecousin.framework.network.mime.entity;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.concurrent.util.AsyncProducer;
import net.lecousin.framework.encoding.charset.CharacterDecoder;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.data.ByteArray;
import net.lecousin.framework.io.data.Chars;
import net.lecousin.framework.math.RangeLong;
import net.lecousin.framework.network.mime.MimeException;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;
import net.lecousin.framework.text.CharArrayStringBuffer;
import net.lecousin.framework.util.Pair;
import net.lecousin.framework.util.Triple;

/**
 * Text entity.
 */
public class TextEntity extends MimeEntity {
	
	public static final String CHARSET_PARAMETER = "charset";

	/** Constructor. */
	public TextEntity(String text, Charset charset, String textMimeType) {
		super(null);
		this.text = text;
		this.charset = charset;
		setHeader(MimeHeaders.CONTENT_TYPE, textMimeType + ";" + CHARSET_PARAMETER + "=" + charset.name());
	}
	
	/** From existing headers. */
	public TextEntity(MimeEntity parent, MimeHeaders from) throws MimeException {
		super(parent, from);
		this.text = "";
		ParameterizedHeaderValue type = headers.getContentType();
		if (type == null || type.getParameter(CHARSET_PARAMETER) == null)
			charset = StandardCharsets.UTF_8;
		else
			charset = Charset.forName(type.getParameter(CHARSET_PARAMETER));
	}
	
	private String text;
	private Charset charset;
	
	public String getText() {
		return text;
	}
	
	public void setText(String text) {
		this.text = text;
	}
	
	public Charset getCharset() {
		return charset;
	}
	
	/** Set the charset to encode the text. */
	public void setCharset(Charset charset) throws MimeException {
		ParameterizedHeaderValue type = headers.getContentType();
		if (type == null) {
			type = new ParameterizedHeaderValue("text/plain", CHARSET_PARAMETER, charset.name());
			headers.add(MimeHeaders.CONTENT_TYPE, type);
		} else {
			if (charset.equals(this.charset)) return;
			type.setParameterIgnoreCase(CHARSET_PARAMETER, charset.name());
			headers.set(MimeHeaders.CONTENT_TYPE, type);
		}
		this.charset = charset;
	}
	
	@Override
	public AsyncSupplier<Pair<Long, AsyncProducer<ByteBuffer, IOException>>, IOException> createBodyProducer() {
		byte[] body = text.getBytes(charset);
		return new AsyncSupplier<>(new Pair<>(Long.valueOf(body.length), new AsyncProducer.SingleData<>(ByteBuffer.wrap(body))), null);
	}
	
	@Override
	public boolean canProduceBodyRange() {
		return true;
	}
	
	@Override
	public Triple<RangeLong, Long, BinaryEntity> createBodyRange(RangeLong range) {
		byte[] body = text.getBytes(charset);
		RangeLong r = new RangeLong(range.min, range.max);
		if (r.min == -1) {
			r.min = body.length - r.max;
			r.max = body.length - 1L;
		} else if (r.max == -1 || r.max > body.length - 1) {
			r.max = body.length - 1L;
		}
		byte[] subBody;
		if (r.min == 0 && r.max == body.length - 1)
			subBody = body;
		else {
			subBody = new byte[(int)(r.max - r.min + 1)];
			System.arraycopy(body, (int)r.min, subBody, 0, subBody.length);
		}
		BinaryEntity subEntity = new BinaryEntity(null, new MimeHeaders(getHeaders().getHeaders()));
		subEntity.setContent(new ByteArrayIO(subBody, "range of text entity"));
		return new Triple<>(r, Long.valueOf(body.length), subEntity);
	}
	
	@Override
	public AsyncConsumer<ByteBuffer, IOException> createConsumer(Long size) {
		return new Consumer(size == null ? 1024 : size.longValue() < 65536 ? size.intValue() : 65536);
	}
	
	/** Consumer to parse the body. */
	public class Consumer implements AsyncConsumer<ByteBuffer, IOException> {
		/** Constructor. */
		public Consumer(int bufferSize) {
			try {
				decoder = CharacterDecoder.get(charset, bufferSize);
			} catch (Exception e) {
				error = IO.error(e);
			}
		}
		
		private CharacterDecoder decoder;
		private CharArrayStringBuffer string = new CharArrayStringBuffer();
		private IOException error;

		@Override
		public IAsync<IOException> consume(ByteBuffer data) {
			if (error != null) return new Async<>(error);
			Chars.Readable chars = decoder.decode(ByteArray.fromByteBuffer(data));
			chars.get(string, chars.remaining());
			return new Async<>(true);
		}

		@Override
		public IAsync<IOException> end() {
			if (error != null) return new Async<>(error);
			Chars.Readable chars = decoder.flush();
			if (chars != null)
				chars.get(string, chars.remaining());
			text = string.toString();
			return new Async<>(true);
		}

		@Override
		public void error(IOException error) {
			// nothing
		}
	}

}
