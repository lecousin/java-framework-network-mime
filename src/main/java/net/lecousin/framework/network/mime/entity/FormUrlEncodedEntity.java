package net.lecousin.framework.network.mime.entity;

import java.io.EOFException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.synch.SynchronizationPoint;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.text.BufferedReadableCharacterStream;
import net.lecousin.framework.util.Pair;

/** Form parameters using x-www-form-urlencoded format. */
public class FormUrlEncodedEntity implements MimeEntity {

	protected List<Pair<String, String>> parameters = new LinkedList<>();
	
	/** Add a parameter. */
	public void add(String name, String value) {
		parameters.add(new Pair<>(name, value));
	}
	
	public List<Pair<String, String>> getParameters() {
		return parameters;
	}

	/** Parse the given source. */
	public SynchronizationPoint<IOException> parse(IO.Readable source, Charset charset) {
		@SuppressWarnings("resource")
		BufferedReadableCharacterStream stream = new BufferedReadableCharacterStream(source, charset, 512, 8);
		SynchronizationPoint<IOException> result = new SynchronizationPoint<>();
		new Task.Cpu<Void, NoException>(
			"Parsing www-form-urlencoded",
			source.getPriority(),
			(res) -> { stream.closeAsync(); }
		) {
			@Override
			public Void run() {
				StringBuilder name = new StringBuilder();
				StringBuilder value = new StringBuilder();
				boolean inValue = false;
				do {
					char c;
					try { c = stream.read(); }
					catch (EOFException eof) {
						if (name.length() > 0 || value.length() > 0)
							try {
								parameters.add(new Pair<>(
									URLDecoder.decode(name.toString(), "UTF-8"),
									URLDecoder.decode(value.toString(), "UTF-8")
								));
							} catch (UnsupportedEncodingException e) {
								// should never happen
							}
						break;
					} catch (IOException error) {
						result.error(error);
						return null;
					}
					if (c == '&') {
						if (name.length() > 0 || value.length() > 0)
							try {
								parameters.add(new Pair<>(
									URLDecoder.decode(name.toString(), "UTF-8"),
									URLDecoder.decode(value.toString(), "UTF-8")
								));
							} catch (UnsupportedEncodingException e) {
								// should never happen
							}
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
				} while (true);
				result.unblock();
				return null;
			}
		}.startOn(stream.canStartReading(), true);
		return result;
	}
	
	@Override
	public String getContentType() {
		return "application/x-www-form-urlencoded; charset=utf-8";
	}
	
	@Override
	public List<Pair<String, String>> getAdditionalHeaders() {
		return new ArrayList<>(0);
	}
	
	@Override
	public IO.Readable getReadableStream() {
		StringBuilder s = new StringBuilder(1024);
		for (Pair<String, String> param : parameters) {
			if (s.length() > 0) s.append('&');
			try {
				s.append(URLEncoder.encode(param.getValue1(), "UTF-8"));
				s.append('=');
				s.append(URLEncoder.encode(param.getValue2(), "UTF-8"));
			} catch (UnsupportedEncodingException e) {
				// should never happen
			}
		}
		byte[] content = s.toString().getBytes(StandardCharsets.UTF_8);
		return new ByteArrayIO(content, "form urlencoded");
	}
	
}
