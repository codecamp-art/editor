package org.example;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.xpath.XPathFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Attr;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight in-place XML editor that preserves formatting, comments, EOLs and encoding.
 * Supports replacing / clearing node or attribute values chosen by XPath, as well as deleting the entire
 * line containing the node/attribute.
 * <p>All methods are stateless and thread-safe.</p>
 */
public final class XmlInPlaceEditor {
    private XmlInPlaceEditor() {}

    /* ---------------------------- PUBLIC API --------------------------- */

    public static void setValue(File file, String xpath, String expectedOldValue, String newValue, String encodingHint) throws IOException {
        byte[] original = Files.readAllBytes(file.toPath());
        byte[] mutated = setValueInternal(original, xpath, expectedOldValue, newValue, encodingHint);
        Files.write(file.toPath(), mutated);
    }

    public static byte[] setValue(InputStream in, String xpath, String expectedOldValue, String newValue, String encodingHint) throws IOException {
        byte[] original = in.readAllBytes();
        return setValueInternal(original, xpath, expectedOldValue, newValue, encodingHint);
    }

    public static void removeLine(File file, String xpath, String expectedOldValue, String encodingHint) throws IOException {
        byte[] original = Files.readAllBytes(file.toPath());
        byte[] mutated = removeLineInternal(original, xpath, expectedOldValue, encodingHint);
        Files.write(file.toPath(), mutated);
    }

    public static byte[] removeLine(InputStream in, String xpath, String expectedOldValue, String encodingHint) throws IOException {
        byte[] original = in.readAllBytes();
        return removeLineInternal(original, xpath, expectedOldValue, encodingHint);
    }

    /* ---------------------------- INTERNALS --------------------------- */

    private static byte[] setValueInternal(byte[] originalBytes, String xpathExpr, String expectedOldValue, String newValue, String encodingHint) throws IOException {
        FileInfo info = FileInfo.detect(originalBytes);
        Charset cs = encodingHint == null ? info.charset : Charset.forName(encodingHint);
        boolean clearOnly = newValue == null;

        String xml = new String(originalBytes, info.offset, originalBytes.length - info.offset, cs);
        XmlTarget target = locate(xml, cs, xpathExpr);
        if (expectedOldValue != null && !Objects.equals(target.currentValue, expectedOldValue)) {
            throw new IllegalArgumentException("Old value mismatch. Expected="+expectedOldValue+", actual="+target.currentValue);
        }

        if (clearOnly && !target.isAttribute) {
            // Clear element content but keep its tags
            String pattern = "(<\\s*"+Pattern.quote(target.tagName)+"[^>]*?>)(.*?)(</\\s*"+Pattern.quote(target.tagName)+"\\s*>)";
            String mutated = xml.replaceFirst(pattern, "$1$3");
            return mergeWithBom(info, mutated.getBytes(cs));
        }

        String mutated;
        if (target.isAttribute) {
            String pattern = Pattern.quote(target.attrName) + "\\s*=\\s*\"" + Pattern.quote(target.currentValue) + "\"";
            mutated = xml.replaceFirst(pattern, target.attrName + "=\"" + (clearOnly?"":Matcher.quoteReplacement(newValue)) + "\"");
        } else {
            String pattern = "(>\\s*)" + Pattern.quote(target.currentValue) + "(\\s*<)";
            mutated = xml.replaceFirst(pattern, "$1" + Matcher.quoteReplacement(newValue) + "$2");
        }

        return mergeWithBom(info, mutated.getBytes(cs));
    }

    private static byte[] removeLineInternal(byte[] originalBytes, String xpathExpr, String expectedOldValue, String encodingHint) throws IOException {
        FileInfo info = FileInfo.detect(originalBytes);
        Charset cs = encodingHint == null ? info.charset : Charset.forName(encodingHint);
        String xml = new String(originalBytes, info.offset, originalBytes.length - info.offset, cs);

        XmlTarget target = locate(xml, cs, xpathExpr);
        if (expectedOldValue != null && !Objects.equals(target.currentValue, expectedOldValue)) {
            throw new IllegalArgumentException("Old value mismatch");
        }

        if (!target.isAttribute) {
            // Remove entire element block (handles self-closing or paired tags)
            String selfPattern = "<\\s*"+Pattern.quote(target.tagName)+"[^>]*?/\\s*>";
            String pairPattern = "<\\s*"+Pattern.quote(target.tagName)+"[^>]*?>.*?</\\s*"+Pattern.quote(target.tagName)+"\\s*>";
            String combined = "(?s)"+pairPattern+"|"+selfPattern;
            String mutated = xml.replaceFirst(combined, "");
            return mergeWithBom(info, mutated.getBytes(cs));
        } else {
            // Attribute removal: remove line containing attribute token
            String[] lines = xml.split("(?<=\\r\\n|\\n|\\r)");
            StringBuilder sb = new StringBuilder(xml.length());
            boolean removed = false;
            for (String line : lines) {
                if (!removed && line.contains(target.matchToken)) { removed=true; continue; }
                sb.append(line);
            }
            if (!removed) throw new IllegalStateException("Line not found");
            return mergeWithBom(info, sb.toString().getBytes(cs));
        }
    }

    /* ------------------------- Helper structures ---------------------- */

    private static final class XmlTarget {
        final boolean isAttribute;
        final String currentValue;
        final String attrName; // only for attributes
        final String matchToken; // search token (attr string or value)
        final String tagName; // for element deletion we rely on tagName
        XmlTarget(boolean attr, String value, String attrName, String matchToken, String tagName){this.isAttribute=attr;this.currentValue=value;this.attrName=attrName;this.matchToken=matchToken;this.tagName=tagName;}
    }

    private static XmlTarget locate(String xml, Charset cs, String xpathExpr) throws IOException {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new ByteArrayInputStream(xml.getBytes(cs)));

            XPath xp = XPathFactory.newInstance().newXPath();
            Object res = xp.evaluate(xpathExpr, doc, XPathConstants.NODESET);
            NodeList nl = (NodeList) res;
            if (nl.getLength()==0) throw new IllegalArgumentException("XPath not found: "+xpathExpr);
            Node node = nl.item(0);
            if (node.getNodeType()==Node.ATTRIBUTE_NODE) {
                Attr a = (Attr) node;
                String token = a.getName()+"=\""+a.getValue()+"\"";
                return new XmlTarget(true,a.getValue(),a.getName(),token,a.getOwnerElement().getNodeName());
            } else {
                String val = node.getTextContent();
                String token = val;
                return new XmlTarget(false,val,null,token,node.getNodeName());
            }
        } catch (Exception e) {
            throw new IOException("Failed to evaluate XPath", e);
        }
    }

    /* ---------------- Encoding / EOL helpers (reused) ----------------- */
    private static byte[] mergeWithBom(FileInfo info, byte[] body) {
        if (info.bom.length>0) {
            byte[] out = new byte[info.bom.length + body.length];
            System.arraycopy(info.bom,0,out,0,info.bom.length);
            System.arraycopy(body,0,out,info.bom.length,body.length);
            return out;
        }
        return body;
    }

    private static final class FileInfo {
        final Charset charset;
        final byte[] bom;
        final int offset;
        private FileInfo(Charset cs, byte[] bom, int offset){this.charset=cs;this.bom=bom;this.offset=offset;}
        static FileInfo detect(byte[] content){
            byte[] UTF8_BOM={(byte)0xEF,(byte)0xBB,(byte)0xBF};
            if(startsWith(content,UTF8_BOM))return new FileInfo(StandardCharsets.UTF_8,UTF8_BOM,3);
            try{new String(content,StandardCharsets.UTF_8);return new FileInfo(StandardCharsets.UTF_8,new byte[0],0);}catch(Exception ignored){}
            return new FileInfo(Charset.forName("GBK"),new byte[0],0);
        }
        private static boolean startsWith(byte[] a, byte[] p){if(a.length<p.length)return false;for(int i=0;i<p.length;i++)if(a[i]!=p[i])return false;return true;}
    }

    /* ---------------- Remove Tag API ---------------- */
    public static void removeTag(File file, String xpath, String expectedOldValue, String encodingHint) throws IOException {
        byte[] original = Files.readAllBytes(file.toPath());
        byte[] mutated = removeTagInternal(original, xpath, expectedOldValue, encodingHint);
        Files.write(file.toPath(), mutated);
    }

    public static byte[] removeTag(InputStream in, String xpath, String expectedOldValue, String encodingHint) throws IOException {
        byte[] original = in.readAllBytes();
        return removeTagInternal(original, xpath, expectedOldValue, encodingHint);
    }

    private static byte[] removeTagInternal(byte[] originalBytes, String xpathExpr, String expectedOldValue, String encodingHint) throws IOException {
        FileInfo info = FileInfo.detect(originalBytes);
        Charset cs = encodingHint == null ? info.charset : Charset.forName(encodingHint);
        String xml = new String(originalBytes, info.offset, originalBytes.length - info.offset, cs);

        XmlTarget target = locate(xml, cs, xpathExpr);
        if (expectedOldValue != null && !Objects.equals(target.currentValue, expectedOldValue)) {
            throw new IllegalArgumentException("Old value mismatch");
        }

        // Remove entire element block (self-closing or paired)
        String selfPattern = "<\\s*"+Pattern.quote(target.tagName)+"[^>]*?/\\s*>";
        String pairPattern = "<\\s*"+Pattern.quote(target.tagName)+"[^>]*?>.*?</\\s*"+Pattern.quote(target.tagName)+"\\s*>";
        String combined = "(?s)"+pairPattern+"|"+selfPattern;
        String mutated = xml.replaceFirst(combined, "");
        // Remove any resulting blank indentation-only line
        mutated = mutated.replaceAll("(?m)^[ \t]*(?:\r?\n|\r)", "");
        return mergeWithBom(info, mutated.getBytes(cs));
    }
} 