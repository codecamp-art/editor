public class DebugXml{
    public static void main(String[] args) throws Exception{
        String xml = "<root>\n  <a></a><b></b>\n</root>";
        org.example.XmlInPlaceEditor.deleteTag(new java.io.File("tmp.xml"), "root/a", null);
    }
} 