package net.lecousin.framework.network.mime.entity;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.synch.AsyncWork;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IOUtil;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.network.mime.MimeMessage;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;
import net.lecousin.framework.util.UnprotectedStringBuffer;

public class TextEntity extends MimeEntity {

	public TextEntity(String text, Charset charset, String textMimeType) {
		this.text = text;
		this.charset = charset;
		setHeaderRaw(CONTENT_TYPE, textMimeType + ";charset=" + charset.name());
	}
	
	protected TextEntity(MimeMessage from) throws Exception {
		super(from);
		this.text = "";
		ParameterizedHeaderValue type = getFirstHeaderValue(CONTENT_TYPE, ParameterizedHeaderValue.class);
		if (type == null || type.getParameter("charset") == null)
			charset = StandardCharsets.UTF_8;
		else
			charset = Charset.forName(type.getParameter("charset"));
	}
	
	@SuppressWarnings("resource")
	public static AsyncWork<TextEntity, IOException> from(MimeMessage mime) {
		TextEntity entity;
		try { entity = new TextEntity(mime); }
		catch (Exception e) { return new AsyncWork<>(null, IO.error(e)); }
		IO.Readable body = mime.getBodyReceivedAsInput();
		if (body == null)
			return new AsyncWork<>(entity, null);
		Task<UnprotectedStringBuffer, IOException> task = IOUtil.readFullyAsString(body, entity.charset, body.getPriority());
		AsyncWork<TextEntity, IOException> result = new AsyncWork<>();
		task.getOutput().listenInline((str) -> {
			entity.text = str.asString();
			result.unblockSuccess(entity);
		}, result);
		result.listenInline(() -> { body.closeAsync(); });
		return result;
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
	
	public void setCharset(Charset charset) throws Exception {
		if (charset.equals(this.charset)) return;
		this.charset = charset;
		ParameterizedHeaderValue type = getFirstHeaderValue(CONTENT_TYPE, ParameterizedHeaderValue.class);
		if (type == null) {
			type = new ParameterizedHeaderValue("text/plain", "charset", charset.name());
			addHeader(CONTENT_TYPE, type);
		} else {
			type.setParameterIgnoreCase("charset", charset.name());
			setHeader(CONTENT_TYPE, type);
		}
	}
	
	@Override
	public IO.Readable getBodyToSend() {
		return new ByteArrayIO(text.getBytes(charset), "TextEntity");
	}
	
}
