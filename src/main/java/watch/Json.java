package watch;

import java.lang.reflect.Array;
import java.util.*;

/** Small JSON codec so the game runs offline with only a JDK. */
public final class Json {
    private Json() {}
    public static Map<String,Object> obj(Object... pairs) {
        Map<String,Object> map = new LinkedHashMap<>();
        for (int i=0; i<pairs.length; i+=2) map.put((String)pairs[i], pairs[i+1]);
        return map;
    }
    public static String encode(Object value) {
        if (value == null) return "null";
        if (value instanceof String || value instanceof Character) {
            StringBuilder b = new StringBuilder("\"");
            for (char c : value.toString().toCharArray()) switch(c) {
                case '"' -> b.append("\\\""); case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n"); case '\r' -> b.append("\\r"); case '\t' -> b.append("\\t");
                default -> { if (c<32) b.append(String.format("\\u%04x", (int)c)); else b.append(c); }
            }
            return b.append('"').toString();
        }
        if (value instanceof Number n) return Double.isFinite(n.doubleValue()) ? n.toString() : "null";
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Map<?,?> m) {
            StringJoiner j = new StringJoiner(",", "{", "}");
            m.forEach((k,v) -> j.add(encode(k.toString())+":"+encode(v))); return j.toString();
        }
        if (value instanceof Iterable<?> items) {
            StringJoiner j = new StringJoiner(",", "[", "]"); for (Object item:items) j.add(encode(item)); return j.toString();
        }
        if (value.getClass().isArray()) {
            StringJoiner j = new StringJoiner(",", "[", "]"); for (int i=0;i<Array.getLength(value);i++) j.add(encode(Array.get(value,i))); return j.toString();
        }
        Map<String,Object> fields = new LinkedHashMap<>();
        for (var field:value.getClass().getFields()) try {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) fields.put(field.getName(),field.get(value));
        } catch (IllegalAccessException e) { throw new IllegalArgumentException(e); }
        return encode(fields);
    }
    public static Map<String,Object> parseObject(String text) {
        Parser p = new Parser(text); Object value = p.value(0); p.space();
        if (p.i!=text.length() || !(value instanceof Map<?,?>)) throw new IllegalArgumentException("Invalid JSON object.");
        @SuppressWarnings("unchecked") Map<String,Object> map = (Map<String,Object>)value; return map;
    }
    private static final class Parser {
        final String s; int i;
        Parser(String s) { this.s=s; }
        void space() { while (i<s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        char take() { if (i>=s.length()) throw new IllegalArgumentException("Incomplete JSON."); return s.charAt(i++); }
        Object value(int depth) {
            if (depth>20) throw new IllegalArgumentException("JSON is too deeply nested.");
            space(); char c=take();
            if (c=='"') return string();
            if (c=='{') {
                Map<String,Object> m=new LinkedHashMap<>(); space(); if (i<s.length() && s.charAt(i)=='}') { i++; return m; }
                do { space(); if (take()!='"') throw new IllegalArgumentException("Invalid JSON key."); String k=string(); space(); if(take()!=':') throw new IllegalArgumentException("Invalid JSON."); m.put(k,value(depth+1)); space(); c=take(); if(c=='}') return m; } while(c==',');
            } else if (c=='[') {
                List<Object> list=new ArrayList<>(); space(); if (i<s.length() && s.charAt(i)==']') { i++; return list; }
                do { list.add(value(depth+1)); space(); c=take(); if(c==']') return list; } while(c==',');
            } else {
                int start=i-1; while(i<s.length() && ",]} \r\n\t".indexOf(s.charAt(i))<0) i++;
                String word=s.substring(start,i);
                return switch(word) { case "true" -> true; case "false" -> false; case "null" -> null; default -> { try { double n=Double.parseDouble(word); if(!Double.isFinite(n)) throw new NumberFormatException(); yield n; } catch(NumberFormatException e) { throw new IllegalArgumentException("Invalid JSON value."); } } };
            }
            throw new IllegalArgumentException("Invalid JSON.");
        }
        String string() {
            StringBuilder b=new StringBuilder();
            for(;;) { char c=take(); if(c=='"') return b.toString(); if(c<32) throw new IllegalArgumentException("Invalid JSON string.");
                if(c=='\\') { c=take(); switch(c) { case '"','\\','/' -> b.append(c); case 'n' -> b.append('\n'); case 'r' -> b.append('\r'); case 't' -> b.append('\t'); case 'b' -> b.append('\b'); case 'f' -> b.append('\f'); case 'u' -> { StringBuilder hex=new StringBuilder(); for(int k=0;k<4;k++) hex.append(take()); b.append((char)Integer.parseInt(hex.toString(),16)); } default -> throw new IllegalArgumentException("Invalid JSON escape."); } } else b.append(c);
            }
        }
    }
}
