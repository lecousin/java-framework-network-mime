package net.lecousin.framework.network.mime.entity;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.concurrent.util.AsyncProducer;
import net.lecousin.framework.encoding.URLEncoding;
import net.lecousin.framework.encoding.charset.CharacterDecoder;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.data.ByteArray;
import net.lecousin.framework.io.data.BytesFromIso8859String;
import net.lecousin.framework.io.data.Chars;
import net.lecousin.framework.io.data.CharsFromString;
import net.lecousin.framework.math.RangeLong;
import net.lecousin.framework.memory.ByteArrayCache;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;
import net.lecousin.framework.util.Pair;
import net.lecousin.framework.util.Triple;

/** Form parameters using x-www-form-urlencoded format. */
public class FormUrlEncodedEntity extends MimeEntity {
	
	/** Constructor. */
	public FormUrlEncodedEntity() {
		super(null);
		headers.addRawValue(MimeHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded; charset=utf-8");
	}
	
	/** Constructor. */
	public FormUrlEncodedEntity(MimeEntity parent, MimeHeaders headers) {
		super(parent, headers);
	}
	
	protected List<Pair<String, String>> parameters = new LinkedList<>();
	
	/** Add a parameter. */
	public void add(String name, String value) {
		parameters.add(new Pair<>(name, value));
	}
	
	/** Return the parameters. */
	public List<Pair<String, String>> getParameters() {
		return parameters;
	}
	
	/** Return true if the parameter is present. */
	public boolean hasParameter(String name) {
		for (Pair<String, String> p : parameters)
			if (p.getValue1().equals(name))
				return true;
		return false;
	}
	
	/** Return the parameter or null if not present. */
	public String getParameter(String name) {
		for (Pair<String, String> p : parameters)
			if (p.getValue1().equals(name))
				return p.getValue2();
		return null;
	}

	@Override
	public AsyncSupplier<Pair<Long, AsyncProducer<ByteBuffer, IOException>>, IOException> createBodyProducer() {
		if (parameters.isEmpty())
			return new AsyncSupplier<>(new Pair<>(Long.valueOf(0), new AsyncProducer.Empty<>()), null);
		AsyncSupplier<Pair<Long, AsyncProducer<ByteBuffer, IOException>>, IOException> result = new AsyncSupplier<>();
		// produce first buffer, if enough we know the size
		BodyProducer producer = new BodyProducer();
		Task.cpu("Produce form-url-encoded", Task.getCurrentPriority(), t -> {
			ByteArray first = producer.nextBuffer();
			if (producer.isDone()) {
				result.unblockSuccess(new Pair<>(
					Long.valueOf(first.remaining()),
					new AsyncProducer.SingleData<>(first.toByteBuffer())));
				return null;
			}
			producer.back(first);
			result.unblockSuccess(new Pair<>(null, producer));
			return null;
		}).start();
		return result;
	}
	
	private class BodyProducer implements AsyncProducer<ByteBuffer, IOException> {
		
		private ByteArray back;
		private Iterator<Pair<String, String>> itParam = parameters.iterator();
		private boolean firstParam = true;
		private Pair<String, String> param;
		private int namePos = 0;
		private int valuePos = 0;
		private boolean equalsDone = false;
		
		public BodyProducer() {
			param = itParam.next();
		}
		
		public void back(ByteArray b) {
			back = b;
		}
		
		public ByteArray nextBuffer() {
			ByteArray.Writable b = new ByteArray.Writable(ByteArrayCache.getInstance().get(2048, true), true);
			do {
				if (!equalsDone) {
					String name = param.getValue1();
					if (namePos < name.length()) {
						CharsFromString chars = new CharsFromString(name);
						chars.setPosition(namePos);
						URLEncoding.encode(chars, b, false, true);
						namePos = chars.position();
						if (namePos < name.length())
							break;
					}
					if (b.hasRemaining()) {
						b.put((byte)'=');
						equalsDone = true;
					}
				} else {
					String value = param.getValue2();
					if (valuePos < value.length()) {
						CharsFromString chars = new CharsFromString(value);
						chars.setPosition(valuePos);
						URLEncoding.encode(chars, b, false, true);
						valuePos = chars.position();
						if (valuePos < value.length())
							break;
					}
					if (!itParam.hasNext()) {
						firstParam = false;
						break;
					}
					if (b.hasRemaining()) {
						b.put((byte)'&');
						firstParam = false;
						param = itParam.next();
						namePos = 0;
						valuePos = 0;
						equalsDone = false;
					}
				}
			} while (b.hasRemaining());
			b.flip();
			return b;
		}
		
		public boolean isDone() {
			return !firstParam && !itParam.hasNext() && equalsDone && valuePos == param.getValue2().length();
		}
		
		@Override
		public AsyncSupplier<ByteBuffer, IOException> produce() {
			if (back != null) {
				ByteArray b = back;
				back = null;
				return new AsyncSupplier<>(b.toByteBuffer(), null);
			}
			if (isDone())
				return new AsyncSupplier<>(null, null);
			AsyncSupplier<ByteBuffer, IOException> result = new AsyncSupplier<>();
			Task.cpu("Produce form-url-encoded", Task.getCurrentPriority(), t -> {
				ByteArray b = nextBuffer();
				result.unblockSuccess(b.toByteBuffer());
				return null;
			}).start();
			return result;
		}
		
	}
	
	@Override
	public boolean canProduceBodyRange() {
		return false;
	}
	
	@Override
	public Triple<RangeLong, Long, BinaryEntity> createBodyRange(RangeLong range) {
		return null;
	}
	
	@Override
	@SuppressWarnings("java:S3358")
	public AsyncConsumer<ByteBuffer, IOException> createConsumer(Long size) {
		return new Parser(size == null ? 1024 : size.longValue() < 65536 ? size.intValue() : 65536);
	}
	
	/** Parser for body. */
	public class Parser implements AsyncConsumer<ByteBuffer, IOException> {
		
		/** Constructor. */
		public Parser(int bufferSize) {
			Charset charset = null;
			try {
				ParameterizedHeaderValue type = headers.getContentType();
				String cs = type.getParameter("charset");
				if (cs != null)
					charset = Charset.forName(cs);
			} catch (Exception e) {
				// ignore
			}
			if (charset == null)
				charset = StandardCharsets.ISO_8859_1;
			try {
				decoder = CharacterDecoder.get(charset, bufferSize);
			} catch (Exception e) {
				error = IO.error(e);
			}
		}
		
		private CharacterDecoder decoder;
		private IOException error;
		private StringBuilder name = new StringBuilder();
		private StringBuilder value = new StringBuilder();
		private boolean inValue = false;

		@Override
		public IAsync<IOException> consume(ByteBuffer data) {
			if (error != null)
				return new Async<>(error);
			Chars.Readable chars = decoder.decode(ByteArray.fromByteBuffer(data));
			decode(chars);
			return new Async<>(true);
		}
		
		@Override
		public IAsync<IOException> end() {
			if (error != null)
				return new Async<>(error);
			Chars.Readable chars = decoder.flush();
			if (chars != null)
				decode(chars);
			if (name.length() > 0 || value.length() > 0)
				addEncodedParameter(name, value);
			return new Async<>(true);
		}
		
		private void decode(Chars.Readable chars) {
			while (chars.hasRemaining()) {
				char c = chars.get();
				if (c == '&') {
					if (name.length() > 0 || value.length() > 0)
						addEncodedParameter(name, value);
					name = new StringBuilder();
					value = new StringBuilder();
					inValue = false;
					continue;
				}
				if (!inValue && c == '=') {
					inValue = true;
					continue;
				}
				if (inValue) value.append(c);
				else name.append(c);
			}
		}
		
		private void addEncodedParameter(StringBuilder name, StringBuilder value) {
			parameters.add(new Pair<>(
				URLEncoding.decode(new BytesFromIso8859String(name)).asString(),
				URLEncoding.decode(new BytesFromIso8859String(value)).asString()
			));
		}
		
		@Override
		public void error(IOException error) {
			this.error = error;
		}
		
	}
	
}
