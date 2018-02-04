package net.lecousin.framework.network.mime.header;

import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.network.mime.MimeUtil;
import net.lecousin.framework.network.mime.header.parser.SpecialCharacter;
import net.lecousin.framework.network.mime.header.parser.Token;
import net.lecousin.framework.network.mime.header.parser.Word;
import net.lecousin.framework.util.Pair;

public class ParameterizedHeaderValue implements HeaderValueFormat {

	public ParameterizedHeaderValue() {
	}

	public ParameterizedHeaderValue(String mainValue, String... parameters) {
		this.mainValue = mainValue;
		for (int i = 0; i < parameters.length - 1; i += 2)
			this.parameters.add(new Pair<>(parameters[i], parameters[i + 1]));
	}
	
	private String mainValue;
	private List<Pair<String, String>> parameters = new LinkedList<>();
	
	public String getMainValue() {
		return mainValue;
	}
	
	public List<Pair<String, String>> getParameters() {
		return parameters;
	}
	
	public String getParameter(String name) {
		for (Pair<String, String> p : parameters)
			if (name.equals(p.getValue1()))
				return p.getValue2();
		return null;
	}
	
	public String getParameterIgnoreCase(String name) {
		for (Pair<String, String> p : parameters)
			if (name.equalsIgnoreCase(p.getValue1()))
				return p.getValue2();
		return null;
	}
	
	public void setMainValue(String value) {
		mainValue = value;
	}
	
	public void addParameter(String name, String value) {
		parameters.add(new Pair<>(name, value));
	}
	
	public void setParameter(String name, String value) {
		for (Pair<String, String> p : parameters)
			if (p.getValue1().equals(name)) {
				p.setValue2(value);
				return;
			}
		parameters.add(new Pair<>(name, value));
	}
	
	public void setParameterIgnoreCase(String name, String value) {
		for (Pair<String, String> p : parameters)
			if (p.getValue1().equalsIgnoreCase(name)) {
				p.setValue2(value);
				return;
			}
		parameters.add(new Pair<>(name, value));
	}
	
	@Override
	public void parseTokens(List<Token> tokens) throws Exception {
		mainValue = null;
		parameters.clear();
		List<List<Token>> params = Token.splitBySpecialCharacter(tokens, ';');
		for (List<Token> param : params) {
			Token.trim(param);
			Token.removeComments(param);
			String s = Token.asText(param);
			int i = s.indexOf('=');
			if (i >= 0) {
				String name = s.substring(0, i).trim();
				String value = MimeUtil.decodeRFC2047(s.substring(i + 1));
				parameters.add(new Pair<>(name, value));
			} else if (mainValue == null) {
				mainValue = MimeUtil.decodeRFC2047(s);
			} else {
				parameters.add(new Pair<>(s, ""));
			}
		}
	}
	
	@Override
	public List<Token> generateTokens() {
		List<Token> list = new LinkedList<>();
		if (mainValue != null) {
			list.add(new Word(MimeUtil.encodeUTF8Value(mainValue)));
		}
		for (Pair<String, String> param : parameters) {
			if (!list.isEmpty())
				list.add(new SpecialCharacter(';'));
			list.add(new Word(param.getValue1()));
			list.add(new Word("="));
			list.add(new Word(MimeUtil.encodeUTF8Value(param.getValue2())));
		}
		return list;
	}
	
}
