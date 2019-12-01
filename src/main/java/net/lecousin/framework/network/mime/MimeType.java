package net.lecousin.framework.network.mime;

import java.util.HashMap;
import java.util.Map;

/** Default MIME type by file extension. */
@SuppressWarnings("squid:S2386") // we keep the maps public
public final class MimeType {
	
	private MimeType() { /* no instance */ }

	public static final String HTML = "text/html";
	public static final String XML = "text/xml";
	public static final String JAVASCRIPT = "text/javascript";
	public static final String JSON = "application/json";
	public static final String CSS = "text/css";
	public static final String PNG = "image/png";
	public static final String JPEG = "image/jpeg";
	public static final String GIF = "image/gif";
	public static final String TXT = "text/plain";
	
	public static final Map<String,String> defaultByExtension = new HashMap<>();
	
	public static final Map<String,String> normalized = new HashMap<>();
	
	static {
		defaultByExtension.put("html", HTML);
		defaultByExtension.put("htm", HTML);
		defaultByExtension.put("xml", XML);
		defaultByExtension.put("js", JAVASCRIPT);
		defaultByExtension.put("json", JSON);
		defaultByExtension.put("css", CSS);
		defaultByExtension.put("png", PNG);
		defaultByExtension.put("jpg", JPEG);
		defaultByExtension.put("jpeg", JPEG);
		defaultByExtension.put("gif", GIF);
		defaultByExtension.put("txt", TXT);
	}
	
}
