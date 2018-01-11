package net.lecousin.framework.network.mime.entity;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.concurrent.synch.AsyncWork;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.buffering.IOInMemoryOrFile;
import net.lecousin.framework.io.encoding.QuotedPrintable;
import net.lecousin.framework.network.mime.MIME;
import net.lecousin.framework.network.mime.MIMEUtil;
import net.lecousin.framework.util.Pair;

/** form-data entity, see RFC 2388. */
public class FormDataEntity extends MultipartEntity {

	/** Constructor. */
	public FormDataEntity() {
		super("form-data");
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
		public PartFile(String fieldName, String filename, String contentType, IO.Readable content) {
			this.fieldName = fieldName;
			this.filename = filename;
			this.contentType = contentType;
			this.content = content;
		}
		
		protected String fieldName;
		protected String filename;
		protected String contentType;
		protected IO.Readable content;
		
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
	public void addFile(String fieldName, String filename, String contentType, IO.Readable content) {
		add(new PartFile(fieldName, filename, contentType, content));
	}
	
	/** Return the fields contained in the form-data. */
	public List<Pair<String, String>> getFields() {
		LinkedList<Pair<String, String>> list = new LinkedList<>();
		for (MimeEntity p : parts)
			if (p instanceof PartField)
				list.add(new Pair<>(((PartField)p).getName(), ((PartField)p).getValue()));
		return list;
	}
	
	@Override
	protected AsyncWork<MimeEntity, IOException> createPart(MIME headers, IOInMemoryOrFile body) {
		// TODO
		return super.createPart(headers, body);
	}

}
