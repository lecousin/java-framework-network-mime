package net.lecousin.framework.network.mime.entity;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.concurrent.util.AsyncProducer;
import net.lecousin.framework.encoding.charset.CharacterDecoder;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.data.ByteArray;
import net.lecousin.framework.io.data.Chars;
import net.lecousin.framework.math.RangeLong;
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
		StringBuilder s = new StringBuilder(1024);
		for (Pair<String, String> param : parameters) {
			if (s.length() > 0) s.append('&');
			try {
				s.append(URLEncoder.encode(param.getValue1(), StandardCharsets.UTF_8.name()));
				s.append('=');
				s.append(URLEncoder.encode(param.getValue2(), StandardCharsets.UTF_8.name()));
			} catch (UnsupportedEncodingException e) {
				// should never happen
			}
		}
		byte[] body = s.toString().getBytes(StandardCharsets.UTF_8);
		return new AsyncSupplier<>(new Pair<>(Long.valueOf(body.length), new AsyncProducer.SingleData<>(ByteBuffer.wrap(body))), null);
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
	public AsyncConsumer<ByteBuffer, IOException> createConsumer() {
		return new Parser(1024);
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
			try {
				parameters.add(new Pair<>(
					URLDecoder.decode(name.toString(), StandardCharsets.UTF_8.name()),
					URLDecoder.decode(value.toString(), StandardCharsets.UTF_8.name())
				));
			} catch (UnsupportedEncodingException e) {
				// should never happen
			}
		}
		
		@Override
		public void error(IOException error) {
			this.error = error;
		}
		
	}
	
}
