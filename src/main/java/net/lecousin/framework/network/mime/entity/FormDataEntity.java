package net.lecousin.framework.network.mime.entity;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.concurrent.synch.AsyncWork;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.LinkedIO;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.buffering.IOInMemoryOrFile;
import net.lecousin.framework.io.encoding.QuotedPrintable;
import net.lecousin.framework.network.mime.MIME;
import net.lecousin.framework.util.Pair;

/** form-data entity, see RFC 2388. */
public class FormDataEntity extends MultipartEntity {

	/** Constructor. */
	public FormDataEntity() {
		super("form-data");
	}
	
	/** Part for a field. */
	public static class PartField implements Part {
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
		
		@SuppressWarnings("resource")
		@Override
		public IO.Readable getReadableStream() {
			StringBuilder s = new StringBuilder(256);
			s.append("Content-Disposition: form-data; name=");
			s.append(MIME.encodeHeaderParameterValue(name, charset));
			s.append("\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\n");
			//s.append("\r\n\r\n");
			byte[] header = s.toString().getBytes(StandardCharsets.US_ASCII);
			s = null;
			ByteBuffer content = QuotedPrintable.encode(value, charset);
			return new LinkedIO.Readable.DeterminedSize("form-data field", new IO.Readable[] {
				new ByteArrayIO(header, "form-data field header"),
				new ByteArrayIO(content.array(), content.remaining(), "form-data field content")
			});
		}
	}
	
	/** Part for a file. */
	public static class PartFile implements Part {
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
		
		public String getContentType() { return contentType; }
		
		public IO.Readable getContent() { return content; }

		@SuppressWarnings("resource")
		@Override
		public IO.Readable getReadableStream() {
			StringBuilder s = new StringBuilder(256);
			s.append("Content-Disposition: form-data; name=");
			s.append(MIME.encodeUTF8HeaderParameterValue(fieldName));
			if (filename != null) {
				s.append("; filename=");
				s.append(MIME.encodeUTF8HeaderParameterValue(filename));
			}
			s.append("\r\n");
			if (contentType != null)
				s.append("Content-Type: ").append(contentType).append("\r\n");
			s.append("\r\n");
			byte[] header = s.toString().getBytes(StandardCharsets.US_ASCII);
			s = null;
			if (content instanceof IO.KnownSize)
				return new LinkedIO.Readable.DeterminedSize("form-data field", new IO.Readable[] {
						new ByteArrayIO(header, "form-data field header"),
						content
					});
			return new LinkedIO.Readable("form-data field", new IO.Readable[] {
				new ByteArrayIO(header, "form-data field header"),
				content
			});
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
		for (Part p : parts)
			if (p instanceof PartField)
				list.add(new Pair<>(((PartField)p).getName(), ((PartField)p).getValue()));
		return list;
	}
	
	@Override
	protected AsyncWork<Part, IOException> createPart(MIME headers, IOInMemoryOrFile body) {
		// TODO
		return super.createPart(headers, body);
	}

}
