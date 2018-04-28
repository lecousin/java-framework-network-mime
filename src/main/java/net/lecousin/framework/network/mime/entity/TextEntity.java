package net.lecousin.framework.network.mime.entity;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import net.lecousin.framework.concurrent.synch.AsyncWork;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IOUtil;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.network.mime.MimeMessage;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;
import net.lecousin.framework.util.UnprotectedStringBuffer;

/**
 * Text entity.
 */
public class TextEntity extends MimeEntity {

	/** Constructor. */
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
	
	/** Parse the body of the given MimeMessage into a TextEntity.
	 * @param fromReceived if true, the received body is parsed, else the body to send is parsed from the mime message.
	 */
	@SuppressWarnings("resource")
	public static AsyncWork<TextEntity, IOException> from(MimeMessage mime, boolean fromReceived) {
		TextEntity entity;
		try { entity = new TextEntity(mime); }
		catch (Exception e) { return new AsyncWork<>(null, IO.error(e)); }
		IO.Readable body = fromReceived ? mime.getBodyReceivedAsInput() : mime.getBodyToSend();
		if (body == null)
			return new AsyncWork<>(entity, null);
		AsyncWork<UnprotectedStringBuffer, IOException> task = IOUtil.readFullyAsString(body, entity.charset, body.getPriority());
		AsyncWork<TextEntity, IOException> result = new AsyncWork<>();
		task.listenInline((str) -> {
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
	
	/** Set the charset to encode the text. */
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
