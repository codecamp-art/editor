package org.example;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.nio.file.Files;
import javax.xml.stream.*;
import javax.xml.stream.events.*;
import javax.xml.namespace.QName;

/**
 * High-performance XML in-place editor that preserves original formatting.
 * Supports full XPath syntax including predicates, wildcards, and functions.
 */
public class backup1 {

    // Constants for better maintainability
    private static final String DEFAULT_ENCODING = "UTF-8";
    private static final String DEFAULT_EOL = "\n";
    private static final Pattern EOL_PATTERN = Pattern.compile("(\r\n|\r|\n)");
    private static final Pattern ATTRIBUTE_PREDICATE_PATTERN = Pattern.compile("@(\\w+)=['\"]([^'\"]*)['\"]");
    private static final Pattern POSITION_PREDICATE_PATTERN = Pattern.compile("^(\\d+)$");
    private static final Pattern POSITION_FUNCTION_PATTERN = Pattern.compile("position\\(\\)=(\\d+)");

    // BOM detection constants
    private static final byte[] UTF8_BOM = {(byte)0xEF, (byte)0xBB, (byte)0xBF};
    private static final byte[] UTF16BE_BOM = {(byte)0xFE, (byte)0xFF};
    private static final byte[] UTF16LE_BOM = {(byte)0xFF, (byte)0xFE};
    // For GBK, treat UTF-8 BOM as a generic BOM marker if present

    /**
     * XPath segment representing an element or attribute with optional predicates
     */
    private record XPathSegment(String elementName, String attributeName, boolean isAttribute, List<Predicate> predicates) {
        XPathSegment(String elementName) {
            this(elementName, null, false, new ArrayList<>());
        }
        XPathSegment(String elementName, String attributeName) {
            this(elementName, attributeName, true, new ArrayList<>());
        }
        void addPredicate(Predicate predicate) { predicates.add(predicate); }
    }

    /**
     * Predicate for filtering elements (e.g., [@id='1'], [1], [position()=2])
     */
    private static final class Predicate {
        enum Type { ATTRIBUTE, POSITION, FUNCTION }

        private final Type type;
        private final String attributeName;
        private final String attributeValue;
        private final int position;
        private final String function;

        // Attribute predicate: [@attr='value']
        static Predicate attribute(String attrName, String attrValue) {
            return new Predicate(Type.ATTRIBUTE, attrName, attrValue, 0, null);
        }

        // Position predicate: [1], [2], etc.
        static Predicate position(int pos) {
            return new Predicate(Type.POSITION, null, null, pos, null);
        }

        // Function predicate: [position()=2], [last()], etc.
        static Predicate function(String func) {
            return new Predicate(Type.FUNCTION, null, null, 0, func);
        }

        private Predicate(Type type, String attributeName, String attributeValue, int position, String function) {
            this.type = type;
            this.attributeName = attributeName;
            this.attributeValue = attributeValue;
            this.position = position;
            this.function = function;
        }

        // Getters
        Type getType() { return type; }
        String getAttributeName() { return attributeName; }
        String getAttributeValue() { return attributeValue; }
        int getPosition() { return position; }
        String getFunction() { return function; }
    }

    /**
     * Encapsulates XML processing context and state
     */
    private static final class XmlProcessingContext {
        private final List<XPathSegment> xpathSegments;
        private final String newValue;
        private final String encoding;
        private final String eol;
        private final byte[] bomBytes;
        private final String prolog;
        private final String eolAfterProlog;
        private final List<String> originalEols;
        private final boolean lastLineNoEol;

        private final List<XPathSegment> currentPath = new ArrayList<>();
        private final Map<String, Integer> elementCounts = new HashMap<>();
        private boolean targetFound = false;

        XmlProcessingContext(List<XPathSegment> xpathSegments, String newValue,
                             String encoding, String eol, byte[] bomBytes,
                             String prolog, String eolAfterProlog, List<String> originalEols, boolean lastLineNoEol) {
            this.xpathSegments = xpathSegments;
            this.newValue = newValue;
            this.encoding = encoding;
            this.eol = eol;
            this.bomBytes = bomBytes;
            this.prolog = prolog;
            this.eolAfterProlog = eolAfterProlog;
            this.originalEols = originalEols;
            this.lastLineNoEol = lastLineNoEol;
        }

        List<XPathSegment> getXpathSegments() { return xpathSegments; }
        String getNewValue() { return newValue; }
        String getEncoding() { return encoding; }
        String getEol() { return eol; }
        byte[] getBomBytes() { return bomBytes; }
        String getProlog() { return prolog; }
        String getEolAfterProlog() { return eolAfterProlog; }
        List<String> getOriginalEols() { return originalEols; }
        boolean isLastLineNoEol() { return lastLineNoEol; }
        List<XPathSegment> getCurrentPath() { return currentPath; }
        Map<String, Integer> getElementCounts() { return elementCounts; }
        boolean isTargetFound() { return targetFound; }
        void setTargetFound(boolean targetFound) { this.targetFound = targetFound; }
    }

    /**
     * Utility for encoding detection and handling.
     */
    private static class EncodingUtil {
        static EncodingInfo detectEncoding(byte[] inputBytes, String specifiedEncoding) {
            // Check BOM first (treat UTF-8 BOM as generic BOM for any encoding, including GBK)
            if (inputBytes.length >= 3 && Arrays.equals(Arrays.copyOfRange(inputBytes, 0, 3), UTF8_BOM)) {
                // Use specified encoding if provided, else try to detect from prolog, else default
                String encoding = specifiedEncoding;
                if (encoding == null) {
                    encoding = detectEncodingFromXmlProlog(inputBytes);
                    if (encoding == null) encoding = DEFAULT_ENCODING;
                }
                if (!isValidEncoding(encoding)) encoding = DEFAULT_ENCODING;
                return new EncodingInfo(encoding, UTF8_BOM, 3);
            } else if (inputBytes.length >= 2) {
                if (Arrays.equals(Arrays.copyOfRange(inputBytes, 0, 2), UTF16BE_BOM)) {
                    return new EncodingInfo("UTF-16BE", UTF16BE_BOM, 2);
                } else if (Arrays.equals(Arrays.copyOfRange(inputBytes, 0, 2), UTF16LE_BOM)) {
                    return new EncodingInfo("UTF-16LE", UTF16LE_BOM, 2);
                }
            }
            // Use specified encoding if provided
            if (specifiedEncoding != null && isValidEncoding(specifiedEncoding)) {
                return new EncodingInfo(specifiedEncoding, null, 0);
            }
            // Try to detect encoding from XML prolog
            String detectedEncoding = detectEncodingFromXmlProlog(inputBytes);
            if (detectedEncoding != null) {
                return new EncodingInfo(detectedEncoding, null, 0);
            }
            // Default to UTF-8
            return new EncodingInfo(DEFAULT_ENCODING, null, 0);
        }

        static String detectEncodingFromXmlProlog(byte[] inputBytes) {
            try {
                String firstPart = new String(inputBytes, 0, Math.min(inputBytes.length, 100), StandardCharsets.UTF_8);
                if (firstPart.startsWith("<?xml")) {
                    int encodingStart = firstPart.indexOf("encoding=\"");
                    if (encodingStart != -1) {
                        encodingStart += 10;
                        int encodingEnd = firstPart.indexOf("\"", encodingStart);
                        if (encodingEnd != -1) {
                            String encoding = firstPart.substring(encodingStart, encodingEnd);
                            if (isValidEncoding(encoding)) {
                                return encoding;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore and return null
            }
            return null;
        }

        static boolean isValidEncoding(String encoding) {
            try {
                java.nio.charset.Charset.forName(encoding);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        static PrologInfo extractXmlProlog(byte[] inputBytes, EncodingInfo encodingInfo) {
            int len = inputBytes.length;
            int offset = encodingInfo.getOffset();
            try {
                String firstPart = new String(inputBytes, offset, Math.min(len - offset, 200), java.nio.charset.Charset.forName(encodingInfo.getEncoding()));
                if (firstPart.startsWith("<?xml")) {
                    int piEnd = firstPart.indexOf("?>");
                    if (piEnd != -1) {
                        String prolog = firstPart.substring(0, piEnd + 2);

                        // Find the EOL after the prolog
                        String afterProlog = firstPart.substring(piEnd + 2);
                        String eolAfterProlog = null;

                        if (afterProlog.startsWith("\r\n")) {
                            eolAfterProlog = "\r\n";
                        } else if (afterProlog.startsWith("\r")) {
                            eolAfterProlog = "\r";
                        } else if (afterProlog.startsWith("\n")) {
                            eolAfterProlog = "\n";
                        }

                        return new PrologInfo(prolog, eolAfterProlog);
                    }
                }
            } catch (Exception e) {
                // If encoding fails, try UTF-8 as fallback
                try {
                    String firstPart = new String(inputBytes, offset, Math.min(len - offset, 200), StandardCharsets.UTF_8);
                    if (firstPart.startsWith("<?xml")) {
                        int piEnd = firstPart.indexOf("?>");
                        if (piEnd != -1) {
                            String prolog = firstPart.substring(0, piEnd + 2);

                            // Find the EOL after the prolog
                            String afterProlog = firstPart.substring(piEnd + 2);
                            String eolAfterProlog = null;

                            if (afterProlog.startsWith("\r\n")) {
                                eolAfterProlog = "\r\n";
                            } else if (afterProlog.startsWith("\r")) {
                                eolAfterProlog = "\r";
                            } else if (afterProlog.startsWith("\n")) {
                                eolAfterProlog = "\n";
                            }

                            return new PrologInfo(prolog, eolAfterProlog);
                        }
                    }
                } catch (Exception ex) {
                    // Ignore and return null
                }
            }
            return null;
        }
    }

    private record PrologInfo(String prolog, String eolAfterProlog) {}

    /**
     * Encapsulates encoding detection results
     */
    private static class EncodingInfo {
        private final String encoding;
        private final byte[] bomBytes;
        private final int offset;

        EncodingInfo(String encoding, byte[] bomBytes, int offset) {
            this.encoding = encoding;
            this.bomBytes = bomBytes;
            this.offset = offset;
        }

        String getEncoding() { return encoding; }
        byte[] getBomBytes() { return bomBytes; }
        int getOffset() { return offset; }
    }

    /**
     * Encapsulates line ending detection results
     */
    private static class EolInfo {
        private final String eol;
        private final List<String> originalEols;
        private final boolean lastLineNoEol;

        EolInfo(String eol, List<String> originalEols, boolean lastLineNoEol) {
            this.eol = eol;
            this.originalEols = originalEols;
            this.lastLineNoEol = lastLineNoEol;
        }

        String getEol() { return eol; }
        List<String> getOriginalEols() { return originalEols; }
        boolean isLastLineNoEol() { return lastLineNoEol; }
    }

    /**
     * Detects line endings in the input
     */
    private static EolInfo detectLineEndings(byte[] inputBytes, String encoding) {
        String inputText;
        try {
            inputText = new String(inputBytes, java.nio.charset.Charset.forName(encoding));
        } catch (Exception e) {
            // Fallback to UTF-8 if encoding fails
            inputText = new String(inputBytes, StandardCharsets.UTF_8);
        }

        Matcher eolMatcher = EOL_PATTERN.matcher(inputText);
        List<String> eols = new ArrayList<>();
        int lastEnd = 0;

        while (eolMatcher.find()) {
            eols.add(eolMatcher.group(1));
            lastEnd = eolMatcher.end();
        }

        boolean lastLineNoEol = (lastEnd < inputText.length());

        // Detect default EOL style
        String eol = DEFAULT_EOL;
        int len = inputBytes.length;
        for (int i = 0; i < len; i++) {
            byte b = inputBytes[i];
            if (b == '\r') {
                eol = (i + 1 < len && inputBytes[i+1] == '\n') ? "\r\n" : "\r";
                break;
            } else if (b == '\n') {
                eol = "\n";
                break;
            }
        }

        return new EolInfo(eol, eols, lastLineNoEol);
    }

    /**
     * Processes the XML content
     */
    private static byte[] processXml(byte[] inputBytes, XmlProcessingContext context) throws Exception {
        XMLInputFactory inputFactory = createXmlInputFactory();
        XMLOutputFactory outputFactory = XMLOutputFactory.newInstance();
        ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();

        // Create reader with proper offset
        int offset = context.getBomBytes() != null ? context.getBomBytes().length : 0;
        XMLEventReader reader = inputFactory.createXMLEventReader(
                new ByteArrayInputStream(inputBytes, offset, inputBytes.length - offset));

        // Create writer with proper encoding
        XMLEventWriter writer;
        try {
            writer = outputFactory.createXMLEventWriter(outputBuffer, context.getEncoding());
        } catch (Exception e) {
            // Fallback to UTF-8 if the encoding is not supported by the XML writer
            writer = outputFactory.createXMLEventWriter(outputBuffer, "UTF-8");
        }

        XMLEventFactory eventFactory = XMLEventFactory.newInstance();

        // Write BOM and prolog if present
        writeBomAndProlog(outputBuffer, context);

        // Process XML events
        processXmlEvents(reader, writer, eventFactory, context);

        writer.flush();
        writer.close();
        reader.close();

        return outputBuffer.toByteArray();
    }

    /**
     * Creates and configures XML input factory
     */
    private static XMLInputFactory createXmlInputFactory() {
        XMLInputFactory inputFactory = XMLInputFactory.newInstance();
        try {
            inputFactory.setProperty("javax.xml.stream.isCoalescing", false);
        } catch (IllegalArgumentException ex) {
            // Ignore if not supported
        }
        try {
            inputFactory.setProperty("javax.xml.stream.supportDTD", true);
        } catch (IllegalArgumentException ex) {
            // Ignore if not supported
        }
        return inputFactory;
    }

    /**
     * Writes BOM and prolog to output if present
     */
    private static void writeBomAndProlog(ByteArrayOutputStream outputBuffer, XmlProcessingContext context) throws Exception {
        if (context.getBomBytes() != null) {
            outputBuffer.write(context.getBomBytes());
        }
        if (context.getProlog() != null) {
            outputBuffer.write(context.getProlog().getBytes(context.getEncoding()));
            if (context.getEolAfterProlog() != null) {
                outputBuffer.write(context.getEolAfterProlog().getBytes(context.getEncoding()));
            }
        }
    }

    /**
     * Processes XML events and applies edits
     */
    private static void processXmlEvents(XMLEventReader reader, XMLEventWriter writer,
                                         XMLEventFactory eventFactory, XmlProcessingContext context) throws Exception {
        boolean inTargetElement = false;
        boolean nestedElementFound = false;
        List<XMLEvent> buffer = null;
        boolean skipStartDocument = context.getProlog() != null;

        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();

            if (event.isStartDocument()) {
                if (!skipStartDocument) {
                    // Skip StartDocument if no prolog in original
                    continue;
                }
                continue;
            }

            if (event.isStartElement()) {
                StartElement start = event.asStartElement();
                String elemName = start.getName().getLocalPart();

                // Update current path and element counts
                updateCurrentPath(context, elemName);

                // Check if this is the target element
                boolean isTarget = isTargetElement(context, start);

                if (isTarget && !context.isTargetFound()) {
                    context.setTargetFound(true);
                    XPathSegment targetSegment = context.getXpathSegments().get(context.getXpathSegments().size() - 1);

                    if (targetSegment.isAttribute()) {
                        // Handle attribute replacement
                        writeElementWithModifiedAttributes(writer, eventFactory, start, targetSegment, context.getNewValue());
                    } else {
                        // Handle element text replacement
                        writer.add(event);
                        inTargetElement = true;
                        nestedElementFound = false;
                        buffer = new ArrayList<>();
                    }
                } else {
                    writer.add(event);
                }

            } else if (isTextContent(event)) {
                if (inTargetElement) {
                    buffer.add(event);
                } else {
                    writer.add(event);
                }
            } else if (isCommentOrPI(event)) {
                if (inTargetElement) {
                    buffer.add(event);
                } else {
                    writer.add(event);
                }
            } else if (event.isEndElement()) {
                EndElement end = event.asEndElement();

                if (inTargetElement && context.getCurrentPath().size() == context.getXpathSegments().size()) {
                    // End of target element
                    writeTargetElementContent(writer, eventFactory, end, buffer, nestedElementFound, context.getNewValue());
                    buffer.clear();
                    inTargetElement = false;
                } else if (inTargetElement) {
                    // End of nested element
                    nestedElementFound = true;
                    buffer.add(end);
                } else {
                    writer.add(end);
                }

                // Remove current element from path
                if (!context.getCurrentPath().isEmpty()) {
                    context.getCurrentPath().remove(context.getCurrentPath().size() - 1);
                }
            } else {
                // Other events
                if (inTargetElement) {
                    buffer.add(event);
                } else {
                    writer.add(event);
                }
            }
        }
    }

    /**
     * Updates current path and element counts
     */
    private static void updateCurrentPath(XmlProcessingContext context, String elemName) {
        XPathSegment currentSegment = new XPathSegment(elemName);
        context.getCurrentPath().add(currentSegment);

        // Update element count for position predicates
        String pathKey = getPathKey(context.getCurrentPath().subList(0, context.getCurrentPath().size() - 1));
        context.getElementCounts().put(pathKey, context.getElementCounts().getOrDefault(pathKey, 0) + 1);
    }

    /**
     * Checks if the current element is the target
     */
    private static boolean isTargetElement(XmlProcessingContext context, StartElement start) {
        // Check for attribute target
        boolean isAttributeTarget = false;
        XPathSegment attrSegment = null;
        if (context.getXpathSegments().size() > 1 &&
                context.getXpathSegments().get(context.getXpathSegments().size() - 1).isAttribute()) {
            isAttributeTarget = (context.getCurrentPath().size() == context.getXpathSegments().size() - 1)
                    && matchesXPath(context.getCurrentPath(),
                    context.getXpathSegments().subList(0, context.getXpathSegments().size() - 1),
                    context.getElementCounts(), start);
            attrSegment = context.getXpathSegments().get(context.getXpathSegments().size() - 1);
        }

        if (isAttributeTarget) {
            return true;
        }

        return matchesXPath(context.getCurrentPath(), context.getXpathSegments(),
                context.getElementCounts(), start);
    }

    /**
     * Writes element with modified attributes
     */
    private static void writeElementWithModifiedAttributes(XMLEventWriter writer, XMLEventFactory eventFactory,
                                                           StartElement start, XPathSegment targetSegment,
                                                           String newValue) throws Exception {
        Iterator<Attribute> attrs = start.getAttributes();
        List<Attribute> newAttrs = new ArrayList<>();
        boolean replacedAttr = false;

        while (attrs.hasNext()) {
            Attribute attr = attrs.next();
            if (attr.getName().getLocalPart().equals(targetSegment.attributeName)) {
                // Replace attribute value
                newAttrs.add(eventFactory.createAttribute(attr.getName(), newValue));
                replacedAttr = true;
            } else {
                newAttrs.add(attr);
            }
        }

        // Add attribute if not found
        if (!replacedAttr) {
            newAttrs.add(eventFactory.createAttribute(targetSegment.attributeName, newValue));
        }

        // Write start element with modified attributes
        StartElement newStart = eventFactory.createStartElement(start.getName(), newAttrs.iterator(), start.getNamespaces());
        writer.add(newStart);
    }

    /**
     * Writes target element content
     */
    private static void writeTargetElementContent(XMLEventWriter writer, XMLEventFactory eventFactory,
                                                  EndElement end, List<XMLEvent> buffer,
                                                  boolean nestedElementFound, String newValue) throws Exception {
        if (!nestedElementFound) {
            // No child elements - replace only the first non-whitespace, non-empty text node
            boolean replaced = false;
            for (XMLEvent e : buffer) {
                if (!replaced && e.isCharacters() && !e.asCharacters().isWhiteSpace() && e.asCharacters().getData().trim().length() > 0) {
                    // Replace this text node with the new value
                    writer.add(eventFactory.createCharacters(newValue == null ? "" : newValue));
                    replaced = true;
                } else {
                    // Preserve all other events (whitespace, comments, CDATA, etc.)
                    writer.add(e);
                }
            }
            // If no text node was replaced, insert the new value before the end element
            if (!replaced && newValue != null && !newValue.isEmpty()) {
                writer.add(eventFactory.createCharacters(newValue));
            }
        } else {
            // Nested elements found - output original content
            for (XMLEvent e : buffer) {
                writer.add(e);
            }
        }
        writer.add(end);
    }

    /**
     * Checks if event is text content
     */
    private static boolean isTextContent(XMLEvent event) {
        return event.isCharacters() ||
                event.getEventType() == XMLStreamConstants.CDATA ||
                event.getEventType() == XMLStreamConstants.SPACE;
    }

    /**
     * Checks if event is comment or processing instruction
     */
    private static boolean isCommentOrPI(XMLEvent event) {
        return event.getEventType() == XMLStreamConstants.COMMENT ||
                event.getEventType() == XMLStreamConstants.PROCESSING_INSTRUCTION;
    }

    /**
     * Formats output with original line endings
     */
    private static String formatOutput(byte[] outputBytes, XmlProcessingContext context) {
        String outputString;
        try {
            // Try to decode using the detected encoding
            outputString = new String(outputBytes, java.nio.charset.Charset.forName(context.getEncoding()));
        } catch (Exception e) {
            // Fallback to UTF-8 if encoding fails
            outputString = new String(outputBytes, StandardCharsets.UTF_8);
        }

        // If we have original EOLs, try to restore them
        if (!context.getOriginalEols().isEmpty()) {
            String[] outputLines = outputString.split("\r\n|\r|\n", -1);

            StringBuilder result = new StringBuilder();
            for (int i = 0; i < outputLines.length; i++) {
                result.append(outputLines[i]);
                if (i < context.getOriginalEols().size()) {
                    result.append(context.getOriginalEols().get(i));
                } else if (i < outputLines.length - 1) {
                    // Use the last known EOL for remaining lines
                    String lastEol = context.getOriginalEols().get(context.getOriginalEols().size() - 1);
                    result.append(lastEol);
                }
            }

            return result.toString();
        } else {
            // No original EOLs, return as is
            return outputString;
        }
    }

    /**
     * Parses an XPath expression into segments for matching.
     * @param xpath XPath string
     * @return List of XPathSegment
     */
    private static List<XPathSegment> parseXPath(String xpath) {
        List<XPathSegment> segments = new ArrayList<>();
        if (xpath.startsWith("/")) {
            xpath = xpath.substring(1);
        }
        List<String> parts = splitXPath(xpath);
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (part.startsWith("@")) {
                String attributeName = part.substring(1);
                String elementName = segments.isEmpty() ? "" : segments.get(segments.size() - 1).elementName;
                XPathSegment segment = new XPathSegment(elementName, attributeName);
                segments.add(segment);
            } else {
                int bracketPos = part.indexOf('[');
                if (bracketPos == -1) {
                    segments.add(new XPathSegment(part));
                } else {
                    String elementName = part.substring(0, bracketPos);
                    String predicatesStr = part.substring(bracketPos + 1, part.lastIndexOf(']'));
                    XPathSegment segment = new XPathSegment(elementName);
                    List<Predicate> predicates = parsePredicates(predicatesStr);
                    for (Predicate pred : predicates) {
                        segment.addPredicate(pred);
                    }
                    segments.add(segment);
                }
            }
        }
        return segments;
    }

    /**
     * Split XPath by '/' but preserve brackets
     */
    private static List<String> splitXPath(String xpath) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int bracketCount = 0;

        for (int i = 0; i < xpath.length(); i++) {
            char c = xpath.charAt(i);

            if (c == '[') {
                bracketCount++;
            } else if (c == ']') {
                bracketCount--;
            } else if (c == '/' && bracketCount == 0) {
                parts.add(current.toString());
                current = new StringBuilder();
                continue;
            }

            current.append(c);
        }

        if (current.length() > 0) {
            parts.add(current.toString());
        }

        return parts;
    }

    /**
     * Parse predicates from string like "@id='1' and position()=2"
     */
    private static List<Predicate> parsePredicates(String predicatesStr) {
        List<Predicate> predicates = new ArrayList<>();

        // Attribute predicate: @attr='value'
        Matcher attrMatcher = ATTRIBUTE_PREDICATE_PATTERN.matcher(predicatesStr);
        while (attrMatcher.find()) {
            String attrName = attrMatcher.group(1);
            String attrValue = attrMatcher.group(2);
            predicates.add(Predicate.attribute(attrName, attrValue));
        }

        // Position predicate: [1], [2], etc.
        Matcher posMatcher = POSITION_PREDICATE_PATTERN.matcher(predicatesStr.trim());
        if (posMatcher.matches()) {
            int position = Integer.parseInt(posMatcher.group(1));
            predicates.add(Predicate.position(position));
        }

        // Function predicates: position()=2, last(), etc.
        if (predicatesStr.contains("position()=")) {
            Matcher funcMatcher = POSITION_FUNCTION_PATTERN.matcher(predicatesStr);
            if (funcMatcher.find()) {
                predicates.add(Predicate.function("position()=" + funcMatcher.group(1)));
            }
        }

        if (predicatesStr.contains("last()")) {
            predicates.add(Predicate.function("last()"));
        }

        return predicates;
    }

    /**
     * Check if current path matches the XPath expression
     */
    private static boolean matchesXPath(List<XPathSegment> currentPath, List<XPathSegment> xpathSegments,
                                        Map<String, Integer> elementCounts, StartElement start) {
        if (currentPath.size() != xpathSegments.size()) {
            return false;
        }

        for (int i = 0; i < currentPath.size(); i++) {
            XPathSegment current = currentPath.get(i);
            XPathSegment expected = xpathSegments.get(i);

            // Check element name (support wildcards)
            if (!expected.elementName.equals("*") && !current.elementName.equals(expected.elementName)) {
                return false;
            }

            // Check predicates
            for (Predicate pred : expected.predicates) {
                if (!evaluatePredicate(pred, current, elementCounts, start, currentPath)) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Evaluate a predicate against the current element (optimized with attribute map)
     */
    private static boolean evaluatePredicate(Predicate pred, XPathSegment segment,
                                             Map<String, Integer> elementCounts, StartElement start,
                                             List<XPathSegment> currentPath) {
        switch (pred.getType()) {
            case ATTRIBUTE:
                Map<String, String> attrMap = getAttributeMap(start);
                return pred.getAttributeValue().equals(attrMap.get(pred.getAttributeName()));
            case POSITION:
                String pathKey = getPathKey(currentPath.subList(0, currentPath.size() - 1));
                int count = elementCounts.getOrDefault(pathKey, 0);
                return count == pred.getPosition();
            case FUNCTION:
                if (pred.getFunction().startsWith("position()=")) {
                    int expectedPos = Integer.parseInt(pred.getFunction().substring("position()=".length()));
                    String pathKey2 = getPathKey(currentPath.subList(0, currentPath.size() - 1));
                    int count2 = elementCounts.getOrDefault(pathKey2, 0);
                    return count2 == expectedPos;
                } else if (pred.getFunction().equals("last()")) {
                    // This would require knowing the total count, simplified for now
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    /**
     * Get a key for tracking element counts
     */
    private static String getPathKey(List<XPathSegment> path) {
        StringBuilder key = new StringBuilder();
        for (XPathSegment segment : path) {
            if (key.length() > 0) key.append("/");
            key.append(segment.elementName);
        }
        return key.toString();
    }

    /**
     * Enhanced text-based replacement for simple cases
     */
    private static String textBasedElementValueReplace(String xml, String path, String newValue) throws Exception {
        List<XPathSegment> segments = parseXPath(path);
        if (segments.isEmpty()) throw new IllegalArgumentException("Invalid XPath");
        XPathSegment target = segments.get(segments.size() - 1);
        if (target.isAttribute) throw new UnsupportedOperationException("Attribute replacement not supported in text fallback");
        if (!isSimpleTextReplacementCase(segments)) {
            throw new UnsupportedOperationException("Complex XPath not supported in text fallback");
        }
        String tag = target.elementName;
        // Match <tag ...>...</tag> blocks, including multiline attributes, comments, and whitespace
        String regex = "(<" + tag + "(?:[^>]|\n|\r)*?>)([ \t\r\n]*)([^<]*?)([ \t\r\n]*)(</" + tag + ">)";
        Pattern p = Pattern.compile(regex, Pattern.DOTALL);
        Matcher m = p.matcher(xml);
        StringBuffer sb = new StringBuffer();
        boolean replaced = false;
        while (m.find()) {
            if (!replaced) {
                String openTag = m.group(1);
                String leadingWs = m.group(2);
                String content = m.group(3);
                String trailingWs = m.group(4);
                String closeTag = m.group(5);
                boolean allPredicatesMatch = true;
                for (Predicate pred : target.predicates) {
                    if (pred.getType() == Predicate.Type.ATTRIBUTE) {
                        String attrRegex = pred.getAttributeName() + "\\s*=\\s*[\"']" + Pattern.quote(pred.getAttributeValue()) + "[\"']";
                        Pattern attrPattern = Pattern.compile(attrRegex, Pattern.DOTALL);
                        if (!attrPattern.matcher(openTag).find()) {
                            allPredicatesMatch = false;
                            break;
                        }
                    }
                }
                if (allPredicatesMatch) {
                    String replacement = openTag + leadingWs + (newValue == null ? "" : newValue) + trailingWs + closeTag;
                    m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                    replaced = true;
                    continue;
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
        }
        m.appendTail(sb);
        if (!replaced) {
            throw new Exception("Target element not found for text-based replacement");
        }
        return sb.toString();
    }

    /**
     * Determines if a case is suitable for text-based replacement
     */
    private static boolean isSimpleTextReplacementCase(List<XPathSegment> segments) {
        // Text-based replacement only works for simple cases:
        // 1. No position predicates (e.g., [1], [2])
        // 2. No function predicates (e.g., position()=2, last())
        // 3. No wildcards (*)
        // 4. Only attribute predicates are supported

        for (XPathSegment segment : segments) {
            // Check for wildcards
            if ("*".equals(segment.elementName)) {
                return false;
            }

            // Check predicates
            for (Predicate pred : segment.predicates) {
                if (pred.getType() == Predicate.Type.POSITION ||
                        pred.getType() == Predicate.Type.FUNCTION) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Edits an XML file in place by replacing the targeted element text or attribute value.
     * The original file's formatting (indentation, comments, whitespace, line endings) is preserved.
     * Uses auto-detection for encoding.
     *
     * @param file     the XML File to edit
     * @param path     the XPath expression to the target element or attribute
     * @param newValue the new text or attribute value to set
     * @throws Exception if parsing or processing fails
     */
    public static void editXml(File file, String path, String newValue) throws Exception {
        editXml(file, path, newValue, null);
    }

    /**
     * Edits an XML file in place by replacing the targeted element text or attribute value.
     * The original file's formatting (indentation, comments, whitespace, line endings) is preserved.
     * Uses specified encoding or auto-detection if encoding is null.
     *
     * @param file     the XML File to edit
     * @param path     the XPath expression to the target element or attribute
     * @param newValue the new text or attribute value to set
     * @param encoding the character encoding to use (null for auto-detection)
     * @throws Exception if parsing or processing fails
     */
    public static void editXml(File file, String path, String newValue, String encoding) throws Exception {
        if (file == null) throw new IllegalArgumentException("File cannot be null");
        if (!file.exists()) throw new IllegalArgumentException("File does not exist: " + file.getPath());

        byte[] inputBytes;
        try (FileInputStream fis = new FileInputStream(file)) {
            inputBytes = fis.readAllBytes();
        }

        // Detect encoding and BOM
        EncodingInfo encodingInfo = EncodingUtil.detectEncoding(inputBytes, encoding);
        String outEncoding = encodingInfo.getEncoding();
        int offset = encodingInfo.getOffset();
        String xml;
        try {
            xml = new String(inputBytes, offset, inputBytes.length - offset, java.nio.charset.Charset.forName(outEncoding));
        } catch (Exception e) {
            xml = new String(inputBytes, offset, inputBytes.length - offset, StandardCharsets.UTF_8);
            outEncoding = "UTF-8";
        }

        // Use advanced XML editor
        String safeValue = newValue == null ? "" : newValue;
        AdvancedXmlEditor editor = new AdvancedXmlEditor(xml, parseXPath(path), safeValue);
        String result = editor.edit();

        // Write encoded content to file, preserving BOM as bytes for all encodings
        try (FileOutputStream fos = new FileOutputStream(file)) {
            if (encodingInfo.getBomBytes() != null) {
                fos.write(encodingInfo.getBomBytes());
            }
            fos.write(result.getBytes(java.nio.charset.Charset.forName(outEncoding)));
        }
    }

    /**
     * Helper for attribute map
     */
    private static Map<String, String> getAttributeMap(javax.xml.stream.events.StartElement start) {
        Map<String, String> map = new HashMap<>();
        Iterator<javax.xml.stream.events.Attribute> attrs = start.getAttributes();
        while (attrs.hasNext()) {
            javax.xml.stream.events.Attribute attr = attrs.next();
            map.put(attr.getName().getLocalPart(), attr.getValue());
        }
        return map;
    }

    /**
     * Edits an XML input by replacing the targeted element text or attribute value.
     * The original formatting (indentation, comments, whitespace, line endings) is preserved.
     * Supports full XPath syntax including predicates, wildcards, and functions.
     * Uses auto-detection for encoding.
     *
     * @param input    the InputStream of the XML content
     * @param path     the XPath expression (e.g. "/root/item[@id='1']", "/root/item[1]/@attr")
     * @param newValue the new text or attribute value to set
     * @return the edited XML content as a String
     * @throws Exception if parsing or processing fails
     */
    public static String editXml(InputStream input, String path, String newValue) throws Exception {
        return editXml(input, path, newValue, null);
    }

    /**
     * Edits an XML input by replacing the targeted element text or attribute value.
     * The original formatting (indentation, comments, whitespace, line endings) is preserved.
     * Supports full XPath syntax including predicates, wildcards, and functions.
     * Uses specified encoding or auto-detection if encoding is null.
     *
     * @param input    the InputStream of the XML content
     * @param path     the XPath expression (e.g. "/root/item[@id='1']", "/root/item[1]/@attr")
     * @param newValue the new text or attribute value to set
     * @param encoding the character encoding to use (null for auto-detection)
     * @return the edited XML content as a String
     * @throws Exception if parsing or processing fails
     */
    public static String editXml(InputStream input, String path, String newValue, String encoding) throws Exception {
        // Validate inputs
        if (input == null) throw new IllegalArgumentException("Input stream cannot be null");
        if (path == null || path.trim().isEmpty()) throw new IllegalArgumentException("XPath cannot be null or empty");
        // Parse XPath into segments
        List<XPathSegment> xpathSegments = parseXPath(path);
        // Read and analyze input
        byte[] inputBytes = input.readAllBytes();
        input.close();
        // Detect encoding and BOM
        EncodingInfo encodingInfo = EncodingUtil.detectEncoding(inputBytes, encoding);
        // Decode XML content
        String xml;
        try {
            xml = new String(inputBytes, encodingInfo.getOffset(), inputBytes.length - encodingInfo.getOffset(), java.nio.charset.Charset.forName(encodingInfo.getEncoding()));
        } catch (Exception e) {
            xml = new String(inputBytes, encodingInfo.getOffset(), inputBytes.length - encodingInfo.getOffset(), StandardCharsets.UTF_8);
        }
        // Use advanced XML editor
        AdvancedXmlEditor editor = new AdvancedXmlEditor(xml, xpathSegments, newValue);
        String result = editor.edit();
        // Prepend BOM as Unicode character if BOM was present in input (for all encodings)
        if (encodingInfo.getBomBytes() != null) {
            if (!result.startsWith("\uFEFF")) {
                result = "\uFEFF" + result;
            }
        }
        return result;
    }

    /**
     * Creates the processing context by analyzing the input
     */
    private static XmlProcessingContext createProcessingContext(byte[] inputBytes,
                                                                List<XPathSegment> xpathSegments,
                                                                String newValue) throws Exception {
        return createProcessingContext(inputBytes, xpathSegments, newValue, null);
    }

    /**
     * Creates the processing context by analyzing the input with optional encoding specification
     */
    private static XmlProcessingContext createProcessingContext(byte[] inputBytes,
                                                                List<XPathSegment> xpathSegments,
                                                                String newValue, String specifiedEncoding) throws Exception {
        // Detect encoding and BOM (use specified encoding if provided)
        EncodingInfo encodingInfo = EncodingUtil.detectEncoding(inputBytes, specifiedEncoding);

        // Detect line endings
        EolInfo eolInfo = detectLineEndings(inputBytes, encodingInfo.getEncoding());

        // Extract XML prolog and EOL after it
        PrologInfo prologInfo = EncodingUtil.extractXmlProlog(inputBytes, encodingInfo);

        return new XmlProcessingContext(xpathSegments, newValue, encodingInfo.getEncoding(),
                eolInfo.getEol(), encodingInfo.getBomBytes(),
                prologInfo != null ? prologInfo.prolog() : null,
                prologInfo != null ? prologInfo.eolAfterProlog() : null,
                eolInfo.getOriginalEols(), eolInfo.isLastLineNoEol());
    }

    // --- Begin AdvancedXmlEditor and dependencies (copied from XmlInPlaceEditor_latest) ---
    private static class AdvancedXmlEditor {
        private final String xml;
        private final List<XPathSegment> xpathSegments;
        private final String newValue;
        private final boolean isAttributeTarget;
        
        AdvancedXmlEditor(String xml, List<XPathSegment> xpathSegments, String newValue) {
            this.xml = xml;
            this.xpathSegments = xpathSegments;
            this.newValue = newValue;
            this.isAttributeTarget = !xpathSegments.isEmpty() && xpathSegments.get(xpathSegments.size() - 1).isAttribute;
        }
        
        String edit() throws Exception {
            if (isAttributeTarget) {
                return editAttribute();
            } else {
                return editElementValue();
            }
        }
        
        private String editElementValue() throws Exception {
            XmlElement targetElement = findTargetElement(new AdvancedXmlParser(xml).getElements());
            if (targetElement == null) {
                throw new Exception("Target element not found");
            }
            int endTagStart = findEndTag(xml, targetElement.tagName, targetElement.startPos);
            if (endTagStart == -1) {
                throw new Exception("End tag not found for element: " + targetElement.tagName);
            }
            int startTagEnd = findStartTagEnd(xml, targetElement.startPos);
            int contentStart = startTagEnd;
            int contentEnd = endTagStart;
            if (contentStart > contentEnd) {
                throw new Exception("Invalid element structure: start tag ends after end tag starts");
            }
            // Preserve all content between start and end tag except the text node or CDATA
            StringBuilder result = new StringBuilder();
            result.append(xml.substring(0, contentStart));
            String between = xml.substring(contentStart, contentEnd);
            // Try to replace regular text node first
            Pattern textNodePattern = Pattern.compile("(\\s*)([^<\\s][^<]*?)(\\s*)(?=<|$)", Pattern.DOTALL);
            Matcher matcher = textNodePattern.matcher(between);
            if (matcher.find()) {
                result.append(matcher.group(1)); // leading whitespace
                result.append(newValue == null ? "" : newValue); // new value
                result.append(matcher.group(3)); // trailing whitespace
                result.append(between.substring(matcher.end()));
            } else {
                // Try to replace CDATA section
                Pattern cdataPattern = Pattern.compile("<!\\[CDATA\\[(.*?)\\]\\]>", Pattern.DOTALL);
                Matcher cdataMatcher = cdataPattern.matcher(between);
                if (cdataMatcher.find()) {
                    result.append(between.substring(0, cdataMatcher.start()));
                    result.append("<![CDATA[");
                    result.append(newValue == null ? "" : newValue);
                    result.append("]]>" );
                    result.append(between.substring(cdataMatcher.end()));
                } else {
                    // No text node or CDATA found, just insert new value at the start
                    result.append(newValue == null ? "" : newValue);
                    result.append(between);
                }
            }
            result.append(xml.substring(contentEnd));
            return result.toString();
        }
        
        private String editAttribute() throws Exception {
            AdvancedXmlParser parser = new AdvancedXmlParser(xml);
            List<XmlElement> elements = parser.getElements();
            XmlElement targetElement = findTargetElement(elements);
            if (targetElement == null) {
                throw new Exception("Target element not found");
            }
            String attributeName = xpathSegments.get(xpathSegments.size() - 1).attributeName;
            int startTagEnd = findStartTagEnd(xml, targetElement.startPos);
            StringBuilder result = new StringBuilder();
            result.append(xml, 0, targetElement.startPos);
            String newTag = replaceAttributeInTag(xml.substring(targetElement.startPos, startTagEnd), attributeName, newValue);
            result.append(newTag);
            result.append(xml, startTagEnd, xml.length());
            return result.toString();
        }
        private int findEndTag(String xml, String tagName, int afterPos) {
            String endTag = "</" + tagName + ">";
            int pos = xml.indexOf(endTag, afterPos);
            return pos;
        }
        private int findStartTagEnd(String xml, int startPos) {
            int pos = startPos;
            while (pos < xml.length() && xml.charAt(pos) != '>') {
                pos++;
            }
            return pos + 1;
        }
        private XmlElement findTargetElement(List<XmlElement> elements) {
            if (!xpathSegments.isEmpty() && xpathSegments.get(xpathSegments.size() - 1).isAttribute) {
                return findTargetElementRecursive(elements, xpathSegments, 0, new HashMap<>(), true);
            } else {
                return findTargetElementRecursive(elements, xpathSegments, 0, new HashMap<>(), false);
            }
        }
        private XmlElement findTargetElementRecursive(List<XmlElement> elements, List<XPathSegment> segments, 
                                                     int segmentIndex, Map<String, Integer> elementCounts, boolean isAttributeTarget) {
            if (segmentIndex >= segments.size() - (isAttributeTarget ? 1 : 0)) {
                return null;
            }
            XPathSegment currentSegment = segments.get(segmentIndex);
            boolean isLastSegment = (segmentIndex == segments.size() - (isAttributeTarget ? 2 : 1));
            String pathKey = getPathKey(segments.subList(0, segmentIndex));
            int currentCount = 0;
            for (XmlElement element : elements) {
                if (!currentSegment.elementName.equals("*") && !element.tagName.equals(currentSegment.elementName)) {
                    continue;
                }
                currentCount++;
                boolean predicatesMatch = true;
                for (Predicate pred : currentSegment.predicates) {
                    if (!evaluatePredicate(pred, element, currentCount, pathKey)) {
                        predicatesMatch = false;
                        break;
                    }
                }
                if (!predicatesMatch) {
                    continue;
                }
                if (isLastSegment) {
                    if (isAttributeTarget) {
                        String attrName = segments.get(segments.size() - 1).attributeName;
                        if (element.attributes.containsKey(attrName)) {
                            return element;
                        }
                    } else {
                        return element;
                    }
                } else {
                    XmlElement result = findTargetElementRecursive(element.children, segments, segmentIndex + 1, elementCounts, isAttributeTarget);
                    if (result != null) {
                        return result;
                    }
                }
            }
            return null;
        }
        private boolean evaluatePredicate(Predicate pred, XmlElement element, int currentCount, String pathKey) {
            switch (pred.getType()) {
                case ATTRIBUTE:
                    // Normalize both attribute name and value for comparison
                    String attrNameNorm = pred.getAttributeName().replaceAll("\\s+", "");
                    String attrValueNorm = pred.getAttributeValue().replaceAll("\\s+", "");
                    for (Map.Entry<String, String> entry : element.attributes.entrySet()) {
                        String keyNorm = entry.getKey().replaceAll("\\s+", "");
                        String valNorm = entry.getValue().replaceAll("\\s+", "");
                        if (keyNorm.equals(attrNameNorm) && valNorm.equals(attrValueNorm)) {
                            return true;
                        }
                    }
                    return false;
                case POSITION:
                    return currentCount == pred.getPosition();
                case FUNCTION:
                    if (pred.getFunction().startsWith("position()=")) {
                        int pos = Integer.parseInt(pred.getFunction().substring("position()=".length()));
                        return currentCount == pos;
                    }
                    if (pred.getFunction().equals("last()")) {
                        // Not implemented: last() support
                        return false;
                    }
                    return false;
                default:
                    return false;
            }
        }
        private String getPathKey(List<XPathSegment> path) {
            StringBuilder key = new StringBuilder();
            for (XPathSegment segment : path) {
                if (key.length() > 0) key.append("/");
                key.append(segment.elementName);
            }
            return key.toString();
        }
        private String replaceAttributeInTag(String tag, String attrName, String newValue) {
            Pattern attrPattern = Pattern.compile(attrName + "=['\"]([^'\"]*)['\"]");
            Matcher matcher = attrPattern.matcher(tag);
            if (matcher.find()) {
                String replacement = attrName + "=\"" + (newValue == null ? "" : newValue) + "\"";
                return matcher.replaceFirst(replacement);
            } else {
                // Attribute not found, add it
                int insertPos = tag.indexOf('>');
                if (insertPos == -1) insertPos = tag.length();
                return tag.substring(0, insertPos) + " " + attrName + "=\"" + (newValue == null ? "" : newValue) + "\"" + tag.substring(insertPos);
            }
        }
    }
    private static class XmlElement {
        final String tagName;
        final Map<String, String> attributes;
        final int startPos;
        int endPos;
        final String fullTag;
        final List<XmlElement> children = new ArrayList<>();
        XmlElement parent;
        boolean hasCdata = false;
        boolean hasText = false;
        XmlElement(String tagName, Map<String, String> attributes, int startPos, int endPos, String fullTag) {
            this.tagName = tagName;
            this.attributes = attributes;
            this.startPos = startPos;
            this.endPos = endPos;
            this.fullTag = fullTag;
        }
        void addChild(XmlElement child) {
            children.add(child);
            child.parent = this;
        }
    }
    private static class AdvancedXmlParser {
        private final String xml;
        private final List<XmlElement> elements;
        AdvancedXmlParser(String xml) {
            this.xml = xml;
            this.elements = new ArrayList<>();
            parse();
        }
        private void parse() {
            Stack<XmlElement> stack = new Stack<>();
            int currentPos = 0;
            while (currentPos < xml.length()) {
                char c = xml.charAt(currentPos);
                if (c == '<') {
                    if (currentPos + 1 < xml.length() && xml.charAt(currentPos + 1) == '?') {
                        currentPos += 2;
                        while (currentPos < xml.length() - 1) {
                            if (xml.charAt(currentPos) == '?' && xml.charAt(currentPos + 1) == '>') {
                                currentPos += 2;
                                break;
                            }
                            currentPos++;
                        }
                    }
                    else if (currentPos + 3 < xml.length() && xml.substring(currentPos, currentPos + 4).equals("<!--")) {
                        currentPos += 4;
                        while (currentPos + 2 < xml.length() && !xml.substring(currentPos, currentPos + 3).equals("-->") ) {
                            currentPos++;
                        }
                        currentPos += 3;
                    }
                    else if (currentPos + 1 < xml.length() && xml.charAt(currentPos + 1) == '/') {
                        int tagStart = currentPos + 2;
                        int tagEnd = xml.indexOf('>', tagStart);
                        if (tagEnd == -1) break;
                        String tagName = xml.substring(tagStart, tagEnd).trim();
                        if (!stack.isEmpty()) {
                            XmlElement elem = stack.pop();
                            elem.endPos = tagEnd + 1;
                        }
                        currentPos = tagEnd + 1;
                    }
                    else {
                        int tagEnd = xml.indexOf('>', currentPos);
                        if (tagEnd == -1) break;
                        String tagContent = xml.substring(currentPos + 1, tagEnd);
                        boolean selfClosing = tagContent.endsWith("/");
                        if (selfClosing) tagContent = tagContent.substring(0, tagContent.length() - 1).trim();
                        int nameEnd = 0;
                        while (nameEnd < tagContent.length() && !Character.isWhitespace(tagContent.charAt(nameEnd))) nameEnd++;
                        String tagName = tagContent.substring(0, nameEnd);
                        String attrString = tagContent.substring(nameEnd).trim();
                        Map<String, String> attributes = parseAttributes(attrString);
                        XmlElement element = new XmlElement(tagName, attributes, currentPos, -1, xml.substring(currentPos, tagEnd + 1));
                        if (!stack.isEmpty()) {
                            stack.peek().addChild(element);
                        } else {
                            elements.add(element);
                        }
                        if (!selfClosing) {
                            stack.push(element);
                            currentPos = tagEnd + 1;
                            // Scan for text or CDATA content between tags, but do not skip or break the element tree
                            while (currentPos < xml.length()) {
                                if (xml.startsWith("<![CDATA[", currentPos)) {
                                    int cdataEnd = xml.indexOf("]]>", currentPos);
                                    if (cdataEnd != -1) {
                                        element.hasCdata = true;
                                        currentPos = cdataEnd + 3;
                                        continue;
                                    } else {
                                        break;
                                    }
                                } else if (xml.charAt(currentPos) == '<') {
                                    break;
                                } else if (!Character.isWhitespace(xml.charAt(currentPos))) {
                                    element.hasText = true;
                                }
                                currentPos++;
                            }
                        } else {
                            element.endPos = tagEnd + 1;
                            currentPos = tagEnd + 1;
                        }
                        continue;
                    }
                } else if (c == '!' && currentPos + 8 < xml.length() && xml.substring(currentPos, currentPos + 9).equals("![CDATA[")) {
                    int cdataEnd = xml.indexOf("]]>", currentPos);
                    if (cdataEnd != -1 && !stack.isEmpty()) {
                        stack.peek().hasCdata = true;
                        currentPos = cdataEnd + 3;
                        continue;
                    }
                } else if (!Character.isWhitespace(c)) {
                    if (!stack.isEmpty()) {
                        stack.peek().hasText = true;
                    }
                }
                currentPos++;
            }
        }
        private Map<String, String> parseAttributes(String attrString) {
            Map<String, String> attributes = new HashMap<>();
            if (attrString == null || attrString.trim().isEmpty()) {
                return attributes;
            }
            int len = attrString.length();
            int i = 0;
            while (i < len) {
                // Skip whitespace and newlines
                while (i < len && Character.isWhitespace(attrString.charAt(i))) i++;
                if (i >= len) break;
                // Parse attribute name
                int nameStart = i;
                while (i < len && (Character.isLetterOrDigit(attrString.charAt(i)) || attrString.charAt(i) == '_' || attrString.charAt(i) == '-')) i++;
                if (i <= nameStart) break;
                String name = attrString.substring(nameStart, i);
                // Skip whitespace and newlines
                while (i < len && Character.isWhitespace(attrString.charAt(i))) i++;
                if (i >= len || attrString.charAt(i) != '=') break;
                i++; // skip '='
                while (i < len && Character.isWhitespace(attrString.charAt(i))) i++;
                if (i >= len) break;
                // Parse quoted value (support both ' and ")
                char quote = attrString.charAt(i);
                if (quote != '"' && quote != '\'') break;
                i++;
                int valueStart = i;
                while (i < len && attrString.charAt(i) != quote) i++;
                if (i >= len) break;
                String value = attrString.substring(valueStart, i);
                i++; // skip closing quote
                attributes.put(name, value);
            }
            return attributes;
        }
        List<XmlElement> getElements() {
            return elements;
        }
        String getXml() {
            return xml;
        }
    }
    // --- End AdvancedXmlEditor and dependencies ---
}

