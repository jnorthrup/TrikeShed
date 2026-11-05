import borg.trikeshed.kanban.BoardFileWal;
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations;
import java.nio.charset.StandardCharsets;
public class WalOwners {
    public static void main(String[] args) {
        var files = new JvmFileOperations();
        var first = new BoardFileWal(files, args[0]);
        var one = first.append("retained-first-frame".getBytes(StandardCharsets.UTF_8));
        first.flush();
        var second = new BoardFileWal(files, args[0]);
        var left = first.append("first-owner-flushed".getBytes(StandardCharsets.UTF_8));
        first.flush();
        var right = second.append("other-owner-flushed".getBytes(StandardCharsets.UTF_8));
        second.flush();
        second.close(); first.close();
        System.out.println("{\"initialSequence\":"+one+",\"firstOwnerSequence\":"+left+",\"secondOwnerSequence\":"+right+",\"bothFlushCallsReturned\":true}");
    }
}
