import com.bimvo.app.ImportFiles;
import com.bimvo.app.Recording;
import com.bimvo.app.TranscriptText;
import com.bimvo.app.Utterance;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** No SDK or network required. These are real executions of production core classes. */
public final class CoreTests {
    private static int passed;
    private interface Checked { void run() throws Exception; }
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        System.out.println("PASS: " + label); passed++;
    }
    private static void throwsType(Class<? extends Throwable> type, Checked action, String label) throws Exception {
        try { action.run(); } catch (Throwable error) {
            check(type.isInstance(error), label + " (" + error.getClass().getSimpleName() + ")"); return;
        }
        throw new AssertionError("Expected " + type.getName() + ": " + label);
    }
    private static Recording record(boolean demo, List<Utterance> turns) {
        return new Recording("record-1", "\u041b\u0435\u043a\u0446\u0438\u044f", "lecture.mp3", "id.audio", "audio/mpeg", "hash",
                3, 720_000, 1_000, demo, turns);
    }
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args[0]); Files.createDirectories(output);
        Utterance first=new Utterance(3,"speaker_1",1_250,4_380,"  Hello \"world\"!\nLine\\two\t\u0001 \u041f\u0440\u0438\u0432\u0435\u0442 \ud83c\udfa4  ");
        Utterance second=new Utterance(7,"speaker_2",4_380,6_000,"Another turn.");
        check(first.text.startsWith("Hello"),"text trimmed at boundaries");
        throwsType(IllegalArgumentException.class,()->new Utterance(-1,"speaker_1",0,1,"a"),"negative ID rejected");
        throwsType(IllegalArgumentException.class,()->new Utterance(0,"",0,1,"a"),"empty speaker rejected");
        throwsType(IllegalArgumentException.class,()->new Utterance(0,null,0,1,"a"),"null speaker rejected");
        throwsType(IllegalArgumentException.class,()->new Utterance(0,"speaker_1",-1,1,"a"),"negative start rejected");
        throwsType(IllegalArgumentException.class,()->new Utterance(0,"speaker_1",10,10,"a"),"zero interval rejected");
        throwsType(IllegalArgumentException.class,()->new Utterance(0,"speaker_1",11,10,"a"),"reversed interval rejected");
        throwsType(IllegalArgumentException.class,()->new Utterance(0,"speaker_1",0,10,"  \n"),"blank text rejected");
        throwsType(IllegalArgumentException.class,()->new Utterance(0,"speaker_1",0,10,null),"null text rejected");
        Utterance edited=first.withText("Updated");
        check(edited.startMs==1_250&&edited.endMs==4_380&&edited.speakerId.equals("speaker_1"),"text edit preserves speaker and milliseconds");
        check(first.text.contains("Hello"),"original turn remains immutable");
        Utterance moved=edited.withTiming("speaker_2",2_000,3_000);
        check(moved.id==3&&moved.text.equals("Updated")&&moved.speakerId.equals("speaker_2"),"timing edit preserves turn ID and text");
        check(moved.equals(new Utterance(3,"speaker_2",2_000,3_000,"Updated")),"turn equality uses actual values");
        List<Utterance> source=new ArrayList<>(Arrays.asList(second,first));
        Recording record=record(true,source);
        source.clear();
        check(record.transcript.size()==2,"snapshot does not retain mutable input list");
        check(record.transcript.get(0).id==3,"turns ordered by start time");
        check(record.utterance(7)==second,"selection uses stable ID, not row index");
        check(record.utterance(999)==null,"unknown turn lookup is safe");
        throwsType(UnsupportedOperationException.class,()->record.transcript.clear(),"UI snapshot is immutable");
        check(record.speakers().size()==2,"unique speakers derived from turns");
        check(record.transcriptDurationMs()==60_000,"demo time axis remains one minute after trimming");
        check(record(false,Arrays.asList(first,second)).transcriptDurationMs()==6_000,"non-demo axis derived from actual turns");
        check(record.matches("\u043b\u0435\u043a\u0446\u0438\u044f"),"Cyrillic title search ignores case");
        check(record.matches("LECTURE.MP3"),"original filename searchable");
        check(record.matches("\u041f\u0420\u0418\u0412\u0415\u0422"),"transcript search ignores Cyrillic case");
        check(record.matches("   "),"empty search includes all records");
        check(!record.matches("not_present_983"),"missing search term excluded");
        check(record(false,Collections.emptyList()).transcript.isEmpty(),"unprocessed record has no invented turns");
        check(TranscriptText.clock(0).equals("00:00"),"zero timestamp");
        check(TranscriptText.clock(60_000).equals("01:00"),"minute rollover, not 00:60");
        check(TranscriptText.clock(3_600_000).equals("01:00:00"),"hour rollover");
        check(TranscriptText.clock(3_661_999).equals("01:01:01"),"long clock formatting");
        check(TranscriptText.clock(-1).equals("00:00"),"negative display time clamped");
        check(TranscriptText.preciseClock(1_250).equals("00:01.250"),"millisecond precision displayed");
        check(TranscriptText.preciseClock(1_000).equals("00:01"),"whole-second labels stay compact");
        throwsType(IllegalArgumentException.class,()->TranscriptText.format(Collections.emptyList(),"JSON"),"empty transcript export rejected");
        throwsType(IllegalArgumentException.class,()->TranscriptText.format(record.transcript,"PDF"),"unsupported export format rejected");
        String json=TranscriptText.format(Arrays.asList(second,first),"JSON");
        check(json.contains("\"start_ms\": 1250")&&json.contains("\"end_ms\": 4380"),"JSON times are integer milliseconds");
        check(json.indexOf("1250")<json.indexOf("6000"),"export is chronologically ordered");
        check(json.contains("\\\"world\\\"")&&json.contains("\\n")&&json.contains("\\\\two"),"quotes, newline and backslash escaped");
        check(json.contains("\\u0001"),"JSON control character escaped");
        check(json.contains("\\ud83c\\udfa4"),"Unicode surrogate pair escaped without data loss");
        String srt=TranscriptText.format(record.transcript,"SRT");
        check(srt.startsWith("1\n00:00:01,250 --> 00:00:04,380\n"),"SRT starts at real milliseconds");
        check(srt.contains("\n\n2\n00:00:04,380 --> 00:00:06,000\n"),"SRT has numbered separated cues");
        check(srt.contains("speaker_1:"),"SRT preserves speaker attribution");
        String txt=TranscriptText.format(record.transcript,"TXT");
        check(txt.contains("[00:01.250 - 00:04.380] speaker_1"),"TXT contains both boundaries and speaker");
        Files.writeString(output.resolve("round_trip.json"),json,StandardCharsets.UTF_8);
        Files.writeString(output.resolve("round_trip.srt"),srt,StandardCharsets.UTF_8);
        Files.writeString(output.resolve("round_trip.txt"),txt,StandardCharsets.UTF_8);
        Path temp=Files.createTempDirectory("bimvo-core-");
        try {
            File file=temp.resolve("copy.part").toFile();
            List<Long> progress=new ArrayList<>();
            ImportFiles.CopyResult copied=ImportFiles.copy(new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)),file,()->false,progress::add);
            check(copied.bytes==3&&file.length()==3,"copy writes exact byte count");
            check(copied.sha256.equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),"copy computes known SHA-256");
            check(progress.get(progress.size()-1)==3,"progress ends at actual copied length");
            byte[] bytes=new byte[2_097_157]; new Random(42).nextBytes(bytes);
            ImportFiles.CopyResult big=ImportFiles.copy(new ByteArrayInputStream(bytes),file,()->false,n->{});
            check(big.bytes==bytes.length&&Arrays.equals(Files.readAllBytes(file.toPath()),bytes),"multi-buffer audio preserved byte-for-byte");
            long[] maxRequest={0};
            InputStream chunks=new ByteArrayInputStream(bytes) {
                @Override public synchronized int read(byte[] b,int offset,int length) {
                    maxRequest[0]=Math.max(maxRequest[0],length);
                    return super.read(b,offset,Math.min(17,length));
                }
            };
            ImportFiles.copy(chunks,file,()->false,n->{});
            check(maxRequest[0]<=65_536,"copy buffer never exceeds 64 KiB");
            check(Arrays.equals(Files.readAllBytes(file.toPath()),bytes),"short provider reads handled correctly");
            throwsType(ImportFiles.EmptyFileException.class,()->ImportFiles.copy(new ByteArrayInputStream(new byte[0]),file,()->false,n->{}),"empty audio rejected");
            check(!file.exists(),"empty audio partial file removed");
            throwsType(InterruptedIOException.class,()->ImportFiles.copy(new ByteArrayInputStream(bytes),file,()->true,n->{}),"initial cancellation honored");
            check(!file.exists(),"initially cancelled copy leaves no file");
            AtomicBoolean cancelled=new AtomicBoolean(false);
            throwsType(InterruptedIOException.class,()->ImportFiles.copy(new ByteArrayInputStream(bytes),file,cancelled::get,n->{ if(n>=65_536)cancelled.set(true); }),"mid-copy cancellation honored");
            check(!file.exists(),"mid-copy partial file removed");
            InputStream broken=new InputStream() {
                int reads;
                @Override public int read() throws IOException { throw new IOException("Broken provider"); }
                @Override public int read(byte[] b,int off,int len) throws IOException {
                    if (reads++>0) throw new IOException("Broken provider");
                    Arrays.fill(b,off,off+128,(byte)1); return 128;
                }
            };
            throwsType(IOException.class,()->ImportFiles.copy(broken,file,()->false,n->{}),"provider read error propagated");
            check(!file.exists(),"provider error partial removed");
            File lowSpace=new File(file.getPath()) {
                @Override public File getParentFile() {
                    return new File(super.getParent()) {
                        @Override public long getUsableSpace() { return 0; }
                    };
                }
            };
            throwsType(ImportFiles.NoSpaceException.class,()->ImportFiles.copy(new ByteArrayInputStream(bytes),lowSpace,()->false,n->{}),"low-space guard rejects write");
            check(!lowSpace.exists(),"low-space partial file removed");
            boolean[] closed={false};
            InputStream ownedByCaller=new ByteArrayInputStream(new byte[]{1,2}) {
                @Override public void close() { closed[0]=true; }
            };
            ImportFiles.copy(ownedByCaller,file,()->false,n->{});
            check(!closed[0],"caller owns input stream lifetime");
            ownedByCaller.close();
        } finally {
            try (java.util.stream.Stream<Path> files=Files.walk(temp)) {
                files.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.delete(path);}catch(IOException e){throw new RuntimeException(e);}});
            }
        }
        check(ImportFiles.safeDisplayName("../../secret.wav").equals("secret.wav"),"path traversal stripped from display name");
        check(ImportFiles.safeDisplayName("C:\\Users\\user\\audio.mp3").equals("audio.mp3"),"Windows path stripped");
        check(ImportFiles.safeDisplayName("a\n\u202E.wav").equals("a.wav"),"control and bidi characters removed");
        check(ImportFiles.safeDisplayName("  ").equals("audio"),"blank filename has safe fallback");
        check(ImportFiles.safeDisplayName(null).equals("audio"),"null filename has safe fallback");
        check(ImportFiles.safeDisplayName("a".repeat(500)).length()==200,"oversized filename bounded");
        check(ImportFiles.safeDisplayName("..").equals("audio"),"parent-directory filename rejected");
        System.out.println("RESULT: "+passed+" production-core assertions passed");
    }
}
