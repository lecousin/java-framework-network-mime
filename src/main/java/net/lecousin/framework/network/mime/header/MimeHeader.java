package net.lecousin.framework.network.mime.header;

import java.util.HashMap;
import java.util.Map;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.network.mime.MimeException;
import net.lecousin.framework.text.CharArrayStringBuffer;
import net.lecousin.framework.text.IString;

/** Header of a MIME Message (RFC 822). */
public class MimeHeader {

	/** Constructor. */
	public MimeHeader(String name, String rawValue) {
		this.name = name;
		this.nameLowerCase = name.toLowerCase();
		this.rawValue = rawValue;
	}
	
	/** Constructor. */
	public MimeHeader(String name, HeaderValueFormat value) {
		this.name = name;
		this.nameLowerCase = name.toLowerCase();
		parsed = new HashMap<>(5);
		parsed.put(value.getClass(), value);
	}
	
	private String name;
	private String nameLowerCase;
	private String rawValue;
	private Map<Class<? extends HeaderValueFormat>, HeaderValueFormat> parsed = null;
	
	public String getName() {
		return name;
	}
	
	public String getNameLowerCase() {
		return nameLowerCase;
	}
	
	/** Return the value as a raw string. */
	public String getRawValue() {
		if (rawValue == null && parsed != null) {
			CharArrayStringBuffer s = new CharArrayStringBuffer();
			parsed.values().iterator().next().generate(s, Integer.MAX_VALUE, Integer.MAX_VALUE);
			rawValue = s.toString();
		}
		return rawValue;
	}
	
	/** Return the value parsed into the requested format. */
	public <T extends HeaderValueFormat> T getValue(Class<T> format) throws MimeException {
		if (parsed != null) {
			@SuppressWarnings("unchecked")
			T t = (T)parsed.get(format);
			if (t != null)
				return t;
		} else if (rawValue == null) {
			return null;
		} else {
			parsed = new HashMap<>(5);
		}
		T t;
		try { t = format.newInstance(); }
		catch (Exception e) {
			LCCore.getApplication().getLoggerFactory().getLogger(MimeHeader.class).error("Unable to instantiate header format class", e);
			return null;
		}
		t.parseRawValue(rawValue);
		parsed.put(format, t);
		return t;
	}
	
	/** Set the value as a raw string. */
	public void setRawValue(String raw) {
		parsed = null;
		rawValue = raw;
	}
	
	/** Set the value in a specific format. */
	public <T extends HeaderValueFormat> void setValue(T value) {
		rawValue = null;
		parsed = new HashMap<>(5);
		parsed.put(value.getClass(), value);
	}
	
	/** Generate this header into the given string. */
	public void appendTo(IString s) {
		s.append(name).append(": ");
		if (rawValue == null && parsed != null) {
			parsed.values().iterator().next().generate(s, 80 - name.length() - 2, 79);
		} else {
			s.append(rawValue);
		}
		s.append("\r\n");
	}
	
}
