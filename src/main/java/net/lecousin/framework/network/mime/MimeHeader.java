package net.lecousin.framework.network.mime;

import java.util.HashMap;
import java.util.Map;

import net.lecousin.framework.network.mime.header.HeaderValueFormat;
import net.lecousin.framework.util.IString;

public class MimeHeader {

	public MimeHeader(String name, String rawValue) {
		this.name = name;
		this.nameLowerCase = name.toLowerCase();
		this.rawValue = rawValue;
	}
	
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
	
	public String getRawValue() {
		if (rawValue == null && parsed != null) {
			StringBuilder s = new StringBuilder();
			parsed.values().iterator().next().generate(s, Integer.MAX_VALUE, Integer.MAX_VALUE);
			rawValue = s.toString();
		}
		return rawValue;
	}
	
	public <T extends HeaderValueFormat> T getValue(Class<T> format) throws Exception {
		if (parsed != null) {
			@SuppressWarnings("unchecked")
			T t = (T)parsed.get(format);
			if (t != null)
				return t;
		} else if (rawValue == null)
			return null;
		else
			parsed = new HashMap<>(5);
		T t = format.newInstance();
		t.parseRawValue(rawValue);
		parsed.put(format, t);
		return t;
	}
	
	public void setRawValue(String raw) {
		parsed = null;
		rawValue = raw;
	}
	
	public <T extends HeaderValueFormat> void setValue(T value) {
		rawValue = null;
		parsed = new HashMap<>(5);
		parsed.put(value.getClass(), value);
	}
	
	public void appendTo(StringBuilder s) {
		s.append(name).append(": ");
		if (rawValue == null && parsed != null) {
			parsed.values().iterator().next().generate(s, 80 - name.length() - 2, 79);
		} else
			s.append(rawValue);
		s.append("\r\n");
	}
	
	public void appendTo(IString s) {
		s.append(name).append(": ");
		if (rawValue == null && parsed != null) {
			parsed.values().iterator().next().generate(s, 80 - name.length() - 2, 79);
		} else
			s.append(rawValue);
		s.append("\r\n");
	}
	
}
