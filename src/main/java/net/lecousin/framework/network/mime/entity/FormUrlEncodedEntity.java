package net.lecousin.framework.network.mime.entity;

import java.io.EOFException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.synch.AsyncWork;
import net.lecousin.framework.concurrent.synch.SynchronizationPoint;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.text.BufferedReadableCharacterStream;
import net.lecousin.framework.network.mime.MimeMessage;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;
import net.lecousin.framework.util.Pair;

/** Form parameters using x-www-form-urlencoded format. */
public class FormUrlEncodedEntity extends MimeEntity {

	public FormUrlEncodedEntity() {
		addHeaderRaw(CONTENT_TYPE, "application/x-www-form-urlencoded; charset=utf-8");
	}
	
	protected FormUrlEncodedEntity(MimeMessage from) {
		super(from);
		addHeaderRaw(CONTENT_TYPE, "application/x-www-form-urlencoded; charset=utf-8");
	}
	
	@SuppressWarnings("resource")
	public static AsyncWork<FormUrlEncodedEntity, Exception> from(MimeMessage mime, boolean fromReceived) {
		FormUrlEncodedEntity entity;
		try { entity = new FormUrlEncodedEntity(mime); }
		catch (Exception e) { return new AsyncWork<>(null, e); }
		
		IO.Readable body = fromReceived ? mime.getBodyReceivedAsInput() : mime.getBodyToSend();
		if (body == null)
			return new AsyncWork<>(entity, null);
		Charset charset = null;
		try {
			ParameterizedHeaderValue type = mime.getContentType();
			String cs = type.getParameter("charset");
			if (cs != null)
				charset = Charset.forName(cs);
		} catch (Exception e) {
			// ignore
		}
		if (charset == null)
			charset = StandardCharsets.ISO_8859_1;
		SynchronizationPoint<IOException> parse = entity.parse(body, charset);
		AsyncWork<FormUrlEncodedEntity, Exception> result = new AsyncWork<>();
		parse.listenInlineSP(() -> { result.unblockSuccess(entity); }, result);
		parse.listenInline(() -> { body.closeAsync(); });
		return result;
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
	public IO.Readable getBodyToSend() {
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
