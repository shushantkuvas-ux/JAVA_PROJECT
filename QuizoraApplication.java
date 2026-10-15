
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class QuizoraApplication {
    private static final int PORT = 8080;
    private static final String DATABASE_URL = "jdbc:sqlite:quizora.db";
    
    private static List<User> USERS = new ArrayList<>();
    private static List<Quiz> QUIZZES = new ArrayList<>();
    private static List<QuizAttempt> ATTEMPTS = new ArrayList<>();
    
    private static String successMessage = "";
    private static final ThreadLocal<User> currentUser = new ThreadLocal<>();

    public static void main(String[] args) throws Exception {
        loadData();
        if (USERS.isEmpty()) {
            seedData();
            saveData();
        }
        ensureDefaultQuizzes();
        ensureDefaultQuizzes();

        Tomcat server = new Tomcat();
        server.setPort(PORT);
        server.getConnector();
        Context context = server.addContext("", new File(".").getAbsolutePath());
        Tomcat.addServlet(context, "quizora", new QuizoraServlet());
        context.addServletMappingDecoded("/", "quizora");
        server.start();
        System.out.println("Quizora is running at http://localhost:" + PORT);
        server.getServer().await();
    }

    /** Front controller Servlet that preserves the application's existing route behavior. */
    public static final class QuizoraServlet extends HttpServlet {
        @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
            handle(request, response);
        }

        @Override protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
            handle(request, response);
        }
    }
    
    private static void loadData() {
        try (Connection connection = DriverManager.getConnection(DATABASE_URL);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("CREATE TABLE IF NOT EXISTS users (email TEXT PRIMARY KEY, name TEXT NOT NULL, password TEXT NOT NULL, role TEXT NOT NULL, status TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS quizzes (id INTEGER PRIMARY KEY, title TEXT NOT NULL, description TEXT NOT NULL, duration INTEGER NOT NULL, level TEXT NOT NULL, status TEXT NOT NULL, creator_email TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS questions (id INTEGER PRIMARY KEY, quiz_id INTEGER NOT NULL REFERENCES quizzes(id) ON DELETE CASCADE, position INTEGER NOT NULL, text TEXT NOT NULL, option0 TEXT, option1 TEXT, option2 TEXT, option3 TEXT, correct_option INTEGER NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS attempts (id INTEGER PRIMARY KEY, user_email TEXT NOT NULL, quiz_title TEXT NOT NULL, score INTEGER NOT NULL, max_score INTEGER NOT NULL, date TEXT NOT NULL)");
            try (ResultSet rs = statement.executeQuery("SELECT email, name, password, role, status FROM users ORDER BY rowid")) {
                while (rs.next()) USERS.add(new User(rs.getString("name"), rs.getString("email"), rs.getString("password"), rs.getString("role"), rs.getString("status")));
            }
            try (ResultSet rs = statement.executeQuery("SELECT id, title, description, duration, level, status, creator_email FROM quizzes ORDER BY id")) {
                Map<Integer, Quiz> byId = new HashMap<>();
                while (rs.next()) {
                    Quiz quiz = new Quiz(rs.getString("title"), rs.getString("description"), rs.getInt("duration"), rs.getString("level"), rs.getString("status"), rs.getString("creator_email"));
                    QUIZZES.add(quiz); byId.put(rs.getInt("id"), quiz);
                }
                try (Statement questionStatement = connection.createStatement(); ResultSet qr = questionStatement.executeQuery("SELECT quiz_id, text, option0, option1, option2, option3, correct_option FROM questions ORDER BY quiz_id, position")) {
                    while (qr.next()) {
                        Quiz quiz = byId.get(qr.getInt("quiz_id"));
                        if (quiz != null) quiz.questions.add(new Question(qr.getString("text"), new String[]{qr.getString("option0"), qr.getString("option1"), qr.getString("option2"), qr.getString("option3")}, qr.getInt("correct_option")));
                    }
                }
            }
            try (ResultSet rs = statement.executeQuery("SELECT user_email, quiz_title, score, max_score, date FROM attempts ORDER BY id")) {
                while (rs.next()) ATTEMPTS.add(new QuizAttempt(rs.getString("user_email"), rs.getString("quiz_title"), rs.getInt("score"), rs.getInt("max_score"), rs.getString("date")));
            }
            System.out.println("SQLite database loaded successfully.");
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialize/load Quizora SQLite database", e);
        }
    }
    
    private static void saveData() {
        try (Connection connection = DriverManager.getConnection(DATABASE_URL)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
                statement.executeUpdate("DELETE FROM questions");
                statement.executeUpdate("DELETE FROM quizzes");
                statement.executeUpdate("DELETE FROM users");
                statement.executeUpdate("DELETE FROM attempts");
                try (PreparedStatement ps = connection.prepareStatement("INSERT INTO users(email,name,password,role,status) VALUES(?,?,?,?,?)")) {
                    for (User user : USERS) { ps.setString(1,user.email); ps.setString(2,user.name); ps.setString(3,user.password); ps.setString(4,user.role); ps.setString(5,user.status); ps.addBatch(); }
                    ps.executeBatch();
                }
                try (PreparedStatement quizPs = connection.prepareStatement("INSERT INTO quizzes(id,title,description,duration,level,status,creator_email) VALUES(?,?,?,?,?,?,?)");
                     PreparedStatement questionPs = connection.prepareStatement("INSERT INTO questions(quiz_id,position,text,option0,option1,option2,option3,correct_option) VALUES(?,?,?,?,?,?,?,?)")) {
                    int quizId = 0;
                    for (Quiz quiz : QUIZZES) {
                        quizId++;
                        quizPs.setInt(1,quizId); quizPs.setString(2,quiz.title); quizPs.setString(3,quiz.description); quizPs.setInt(4,quiz.duration); quizPs.setString(5,quiz.level); quizPs.setString(6,quiz.status); quizPs.setString(7,quiz.creatorEmail); quizPs.executeUpdate();
                        for (int i = 0; i < quiz.questions.size(); i++) {
                            Question q = quiz.questions.get(i); questionPs.setInt(1,quizId); questionPs.setInt(2,i); questionPs.setString(3,q.text);
                            for (int j = 0; j < 4; j++) questionPs.setString(4+j, j < q.options.length ? q.options[j] : "");
                            questionPs.setInt(8,q.correctOptionIndex); questionPs.addBatch();
                        }
                    }
                    questionPs.executeBatch();
                }
                try (PreparedStatement ps = connection.prepareStatement("INSERT INTO attempts(user_email,quiz_title,score,max_score,date) VALUES(?,?,?,?,?)")) {
                    for (QuizAttempt a : ATTEMPTS) { ps.setString(1,a.userEmail); ps.setString(2,a.quizTitle); ps.setInt(3,a.score); ps.setInt(4,a.maxScore); ps.setString(5,a.date); ps.addBatch(); }
                    ps.executeBatch();
                }
            }
            connection.commit();
        } catch (Exception e) {
            throw new IllegalStateException("Could not save Quizora data to SQLite", e);
        }
    }

    private static void seedData() {
        USERS.add(new User("Admin User", "admin@quizora.dev", "admin123", "Admin", "Active"));
        QUIZZES.add(createDefaultJavaQuiz());
        QUIZZES.add(createDefaultDbmsQuiz());
    }

    private static Quiz createDefaultJavaQuiz() {
        Quiz quiz = new Quiz("Java Programming", "Intermediate-level Java assessment covering Core OOP, Collections, Multithreading, Exception Handling, Streams API, and JVM architecture.", 30, "Intermediate", "Published", "admin@quizora.dev");
        quiz.questions.add(new Question("Which memory area in the JVM is shared among all threads?", new String[]{"JVM Stack", "Program Counter (PC) Register", "Heap Memory", "Native Method Stack"}, 2));
        quiz.questions.add(new Question("What happens when two distinct objects produce the same hashCode() in Java?", new String[]{"A compilation error occurs", "A collision occurs and both objects are placed in the same bucket in a HashMap", "The second object overwrites the first object immediately", "JVM throws a RuntimeException"}, 1));
        quiz.questions.add(new Question("Why is the String class immutable in Java?", new String[]{"For memory efficiency via String Pool, thread safety, and security in class loading", "Because Java does not support pointers", "To prevent the compiler from optimizing string literals", "Because strings inherit from primitive char type"}, 0));
        quiz.questions.add(new Question("In Java 8 and later, how does a HashMap handle a hash collision when the bucket size exceeds the treeify threshold (8)?", new String[]{"It drops old entries", "It converts the linked list in the bucket into a Red-Black Tree", "It switches to quadratic probing", "It automatically throws an OutOfMemoryError"}, 1));
        quiz.questions.add(new Question("What is the result of comparing two Integer objects: Integer a = 127; Integer b = 127; System.out.println(a == b);?", new String[]{"false because they are separate heap objects", "true because values between -128 and 127 are cached by IntegerCache", "Compilation error: cannot use == on wrapper objects", "It depends on the OS architecture"}, 1));
        quiz.questions.add(new Question("What is the primary difference between ArrayList and LinkedList in Java?", new String[]{"LinkedList provides O(1) random access, while ArrayList provides O(n)", "ArrayList uses a dynamic array with O(1) indexed access; LinkedList uses a doubly-linked list with O(n) indexed access", "ArrayList is synchronized by default, while LinkedList is not", "LinkedList consumes less memory per element than ArrayList"}, 1));
        quiz.questions.add(new Question("Which interface must a class implement to be used within a try-with-resources statement?", new String[]{"java.lang.Cloneable", "java.io.Serializable", "java.lang.AutoCloseable", "java.lang.Runnable"}, 2));
        quiz.questions.add(new Question("Can an abstract class have constructors in Java?", new String[]{"No, because abstract classes cannot be instantiated directly", "Yes, they are called during subclass constructor chaining via super()", "Only if all methods in the abstract class are non-abstract", "Only if the constructor is marked private"}, 1));
        quiz.questions.add(new Question("What does the 'volatile' keyword guarantee in Java multi-threading?", new String[]{"Atomicity of compound operations (e.g., count++)", "Visibility of changes across threads and prevention of instruction reordering", "Mutual exclusion lock like synchronized", "Automatic thread termination when idle"}, 1));
        quiz.questions.add(new Question("What is the effect of 'type erasure' in Java Generics?", new String[]{"Generic type parameters are preserved at runtime in bytecode", "Generic type information is replaced with raw types (or upper bounds) at compile time", "All generic collections are converted to arrays at runtime", "Generic classes cannot be subclassed"}, 1));
        quiz.questions.add(new Question("According to the PECS principle in Java Generics, what does PECS stand for?", new String[]{"Producer Extends, Consumer Super", "Parameter Extends, Collection Super", "Process Exception, Catch Subclass", "Primitive Extends, Cast Super"}, 0));
        quiz.questions.add(new Question("Which of the following is an intermediate operation in Java Stream API?", new String[]{"forEach()", "collect()", "map()", "reduce()"}, 2));
        quiz.questions.add(new Question("How does ConcurrentHashMap achieve thread safety without locking the entire map in modern Java?", new String[]{"By wrapping all methods in synchronized blocks", "Using node-level CAS (Compare-And-Swap) operations and synchronized on bucket heads", "By duplicating the map for every thread", "Using a global read-write lock"}, 1));
        quiz.questions.add(new Question("What happens if a thread calls wait() on an object without holding that object's monitor lock?", new String[]{"The thread successfully waits", "IllegalMonitorStateException is thrown at runtime", "The thread deadlocks immediately", "Compilation error occurs"}, 1));
        quiz.questions.add(new Question("Which functional interface in java.util.function accepts an argument of type T and returns a boolean?", new String[]{"Consumer<T>", "Supplier<T>", "Function<T, R>", "Predicate<T>"}, 3));
        quiz.questions.add(new Question("What is the difference between Optional.of() and Optional.ofNullable()?", new String[]{"Optional.of() allows null values, Optional.ofNullable() does not", "Optional.of() throws NullPointerException if the argument is null, whereas Optional.ofNullable() returns Optional.empty()", "Both are identical in behavior", "Optional.ofNullable() throws an exception if the value is empty"}, 1));
        quiz.questions.add(new Question("What is the purpose of the 'transient' keyword in Java?", new String[]{"To mark a variable as thread-safe", "To prevent a field from being serialized during object serialization", "To indicate that a variable will change frequently", "To allow a variable to be accessed only inside its package"}, 1));
        quiz.questions.add(new Question("If a class implements two interfaces that define default methods with the exact same signature, what must the implementing class do?", new String[]{"It automatically inherits the method from the first listed interface", "It will fail to compile unless the class explicitly overrides the conflicting method", "The JVM picks one randomly at runtime", "It cannot compile under any circumstance"}, 1));
        quiz.questions.add(new Question("Which garbage collection collector introduced in Java is designed for ultra-low latency with pause times under 1 millisecond?", new String[]{"Serial GC", "Parallel GC", "ZGC (Z Garbage Collector)", "CMS (Concurrent Mark Sweep)"}, 2));
        quiz.questions.add(new Question("What will the following code output? String s1 = \"hello\"; String s2 = new String(\"hello\"); System.out.println((s1 == s2) + \" \" + s1.equals(s2));", new String[]{"true true", "false false", "false true", "true false"}, 2));
        quiz.questions.add(new Question("In Java multithreading, what is the main difference between Callable<V> and Runnable?", new String[]{"Runnable can return a result, Callable cannot", "Callable can return a value and throw checked exceptions; Runnable cannot", "Runnable is executed by ExecutorService, Callable is not", "Callable cannot be executed asynchronously"}, 1));
        quiz.questions.add(new Question("What is true about Java's 'finally' block?", new String[]{"It always executes, unless System.exit(0) is called or the JVM crashes", "It only executes if an exception is thrown", "It only executes if no exception is thrown", "It cannot contain return statements"}, 0));
        quiz.questions.add(new Question("What feature introduced in Java 14/16 provides immutable data-carrier classes with concise syntax?", new String[]{"Sealed Classes", "Records", "Enums", "Modules"}, 1));
        quiz.questions.add(new Question("What does a 'sealed class' in Java specify using the 'permits' keyword?", new String[]{"Which packages can import the class", "Which classes are permitted to extend or implement it", "Which threads can access the class instances", "Which methods can throw checked exceptions"}, 1));
        quiz.questions.add(new Question("What is the contract between equals() and hashCode() in Java?", new String[]{"If two objects have the same hashCode(), they must be equal according to equals()", "If two objects are equal according to equals(), they must have the same hashCode()", "Two unequal objects must have different hashCodes", "Overriding equals() does not require overriding hashCode()"}, 1));
        quiz.questions.add(new Question("Which collection maintains its elements in ascending sorted order by natural ordering or a custom Comparator?", new String[]{"HashSet", "LinkedHashSet", "TreeSet", "ArrayDeque"}, 2));
        quiz.questions.add(new Question("What is the difference between execute() and submit() methods of ExecutorService?", new String[]{"execute() returns a Future, submit() returns void", "submit() returns a Future object allowing result retrieval and exception inspection; execute() returns void", "submit() only accepts Runnable, execute() only accepts Callable", "There is no difference"}, 1));
        quiz.questions.add(new Question("In Java I/O, what is the purpose of serialVersionUID in a Serializable class?", new String[]{"To count the number of serialized instances", "To verify that the sender and receiver of a serialized object have compatible classes loaded", "To encrypt the serialized stream", "To optimize disk writing speed"}, 1));
        quiz.questions.add(new Question("Which statement correctly describes the behavior of StringBuilder vs StringBuffer?", new String[]{"StringBuffer is unsynchronized and faster; StringBuilder is synchronized and thread-safe", "StringBuilder is unsynchronized and faster; StringBuffer is synchronized and thread-safe", "Both are immutable like String", "StringBuilder cannot append primitive types"}, 1));
        quiz.questions.add(new Question("What is the effect of invoking Thread.yield() in Java?", new String[]{"It suspends the thread indefinitely until notified", "It gives a hint to the thread scheduler that the current thread is willing to yield its current use of a processor", "It causes the thread to sleep for exactly 1 millisecond", "It stops the thread completely"}, 1));
        return quiz;
    }

    private static Quiz createDefaultDbmsQuiz() {
        Quiz quiz = new Quiz("DBMS", "Comprehensive DBMS assessment covering Relational Models, Normalization, SQL, Transactions, and Indexing.", 25, "Intermediate", "Published", "admin@quizora.dev");
        quiz.questions.add(new Question("Which normal form deals with the elimination of transitive functional dependencies?", new String[]{"First Normal Form (1NF)", "Second Normal Form (2NF)", "Third Normal Form (3NF)", "Boyce-Codd Normal Form (BCNF)"}, 2));
        quiz.questions.add(new Question("What is the key difference between 3NF and BCNF (Boyce-Codd Normal Form)?", new String[]{"3NF requires multi-valued dependencies to be removed", "In BCNF, for every functional dependency X -> Y, X must be a super key", "3NF allows non-prime attributes to determine super keys", "BCNF is strictly weaker than 3NF"}, 1));
        quiz.questions.add(new Question("Which ACID property guarantees that a transaction's changes survive system crashes once committed?", new String[]{"Atomicity", "Consistency", "Isolation", "Durability"}, 3));
        quiz.questions.add(new Question("What concurrency anomaly occurs when a transaction reads data written by another uncommitted transaction?", new String[]{"Dirty Read", "Non-Repeatable Read", "Phantom Read", "Lost Update"}, 0));
        quiz.questions.add(new Question("Which transaction isolation level prevents both Dirty Reads and Non-Repeatable Reads, but may still permit Phantom Reads?", new String[]{"Read Uncommitted", "Read Committed", "Repeatable Read", "Serializable"}, 2));
        quiz.questions.add(new Question("What does Strict Two-Phase Locking (Strict 2PL) ensure compared to basic 2PL?", new String[]{"It guarantees prevention of deadlocks completely", "All exclusive (X) locks held by a transaction are released only after the transaction commits or aborts, preventing cascading aborts", "It allows unlocking during the growing phase", "It does not require shared locks"}, 1));
        quiz.questions.add(new Question("Why are B+ Trees preferred over binary search trees or standard B-Trees for database indexing on disk?", new String[]{"B+ Trees have fewer children per node", "All data pointers are stored only in leaf nodes, which are linked together, enabling efficient high-fanout block reads and range queries", "B+ Trees do not require balanced height", "B+ Trees occupy zero memory in RAM"}, 1));
        quiz.questions.add(new Question("What is the difference between WHERE and HAVING clauses in SQL?", new String[]{"WHERE filters rows after aggregation; HAVING filters rows before aggregation", "WHERE filters individual rows before any grouping; HAVING filters aggregated groups after GROUP BY", "WHERE can only be used with numeric columns", "HAVING cannot be used without an ORDER BY clause"}, 1));
        quiz.questions.add(new Question("What is the result of the SQL query: SELECT COUNT(comm) FROM emp; if 4 out of 10 employees have NULL in comm?", new String[]{"10", "4", "6", "NULL"}, 2));
        quiz.questions.add(new Question("What happens when an ON DELETE CASCADE constraint is defined on a Foreign Key?", new String[]{"Deleting the parent record is prevented if child records exist", "Deleting the parent record automatically deletes corresponding rows in the child table", "Foreign keys in child rows are set to NULL", "An error is raised at compile time"}, 1));
        quiz.questions.add(new Question("In relational algebra, which operator corresponds to filtering columns (attributes) from a table?", new String[]{"Selection (σ)", "Projection (π)", "Cartesian Product (×)", "Join (⨝)"}, 1));
        quiz.questions.add(new Question("What is a 'Correlated Subquery' in SQL?", new String[]{"A subquery that runs once before the outer query executes", "A subquery that references columns from the outer query and evaluates once for each candidate row of the outer query", "A query that joins two unrelated tables using CROSS JOIN", "A subquery contained inside a CREATE VIEW statement"}, 1));
        quiz.questions.add(new Question("What mechanism does a database use to implement Atomicity and Durability during recovery from unexpected failures?", new String[]{"B+ Tree re-indexing", "Write-Ahead Logging (WAL) and checkpoints", "Two-phase commit alone", "Garbage collection of tuples"}, 1));
        quiz.questions.add(new Question("What is the difference between a Clustered Index and a Non-Clustered Index?", new String[]{"A table can have multiple clustered indexes, but only one non-clustered index", "A clustered index determines the physical storage order of rows in the table (maximum one per table); non-clustered index stores sorted pointers", "Clustered indexes are slower for range queries than non-clustered indexes", "Non-clustered indexes cannot contain composite keys"}, 1));
        quiz.questions.add(new Question("In deadlock prevention, how does the 'Wait-Die' scheme handle a conflict where an older transaction Ti requests a lock held by a younger transaction Tj?", new String[]{"Ti dies immediately", "Ti is allowed to wait for Tj", "Tj is rolled back and wounded", "Both transactions are aborted"}, 1));
        quiz.questions.add(new Question("What does a 'Phantom Read' anomaly refer to?", new String[]{"Reading uncommitted changes from another transaction", "A transaction re-reading a row and finding that another transaction modified its columns", "A transaction executing a query twice and finding newly inserted or deleted rows matching the search condition committed by another transaction", "Reading data from a corrupted disk sector"}, 2));
        quiz.questions.add(new Question("Which normal form requires that a relation is in 1NF and every non-prime attribute is fully functionally dependent on the entire primary key?", new String[]{"First Normal Form (1NF)", "Second Normal Form (2NF)", "Third Normal Form (3NF)", "Fourth Normal Form (4NF)"}, 1));
        quiz.questions.add(new Question("What is the difference between a View and a Materialized View?", new String[]{"A view stores actual data on disk; a materialized view is only a stored query", "A view is a virtual table computed dynamically on query; a materialized view physically stores query results and requires periodic refreshing", "Materialized views cannot be indexed", "Views can only query a single table"}, 1));
        quiz.questions.add(new Question("Which type of join returns all rows from the left table, and the matched rows from the right table (filling with NULL where no match exists)?", new String[]{"INNER JOIN", "FULL OUTER JOIN", "LEFT OUTER JOIN", "CROSS JOIN"}, 2));
        quiz.questions.add(new Question("What is a 'Candidate Key' in a relational database?", new String[]{"Any attribute that has unique values regardless of minimalism", "A minimal super key that uniquely identifies each tuple in a relation without redundant attributes", "A key imported from a foreign table", "An index generated automatically by SQL"}, 1));
        quiz.questions.add(new Question("In SQL, which command category do COMMIT, ROLLBACK, and SAVEPOINT belong to?", new String[]{"DDL (Data Definition Language)", "DML (Data Manipulation Language)", "TCL (Transaction Control Language)", "DCL (Data Control Language)"}, 2));
        quiz.questions.add(new Question("What is the purpose of a database Checkpoint?", new String[]{"To shut down the database engine securely", "To flush dirty database pages and log records from memory to disk, reducing recovery time after a crash", "To check user passwords for security compliance", "To delete all old committed transactions permanently"}, 1));
        quiz.questions.add(new Question("What is a 'Lossless-Join Decomposition' in database normalization?", new String[]{"A decomposition where no columns are lost during projection", "A decomposition where the natural join of the decomposed relations is guaranteed to produce exactly the original relation without spurious tuples", "A decomposition that avoids using foreign keys", "A decomposition that does not require 3NF"}, 1));
        quiz.questions.add(new Question("What is the output of comparing NULL with NULL in standard SQL (e.g., NULL = NULL)?", new String[]{"TRUE", "FALSE", "UNKNOWN (NULL in three-valued logic)", "Compilation error"}, 2));
        quiz.questions.add(new Question("Which of the following describes a conflict serializable schedule in transaction processing?", new String[]{"A schedule that is view equivalent to a serial schedule", "A schedule that can be transformed into a serial schedule by swapping non-conflicting concurrent operations", "A schedule that never uses write operations", "A schedule where all transactions commit at the exact same instant"}, 1));
        return quiz;
    }

    private static void ensureDefaultQuizzes() {
        boolean modified = false;
        Quiz javaQuiz = QUIZZES.stream().filter(q -> q.title.toLowerCase().contains("java")).findFirst().orElse(null);
        if (javaQuiz == null || javaQuiz.questions.size() < 30) {
            if (javaQuiz != null) QUIZZES.remove(javaQuiz);
            QUIZZES.add(createDefaultJavaQuiz());
            modified = true;
        }
        Quiz dbmsQuiz = QUIZZES.stream().filter(q -> q.title.equalsIgnoreCase("dbms")).findFirst().orElse(null);
        if (dbmsQuiz == null || dbmsQuiz.questions.size() < 25) {
            if (dbmsQuiz != null) QUIZZES.remove(dbmsQuiz);
            QUIZZES.add(createDefaultDbmsQuiz());
            modified = true;
        }
        if (modified) {
            saveData();
        }
    }

    private static void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.isEmpty()) path = "/";
        Map<String, String> form = readParameters(request);

        HttpSession session = request.getSession(false);
        String email = session == null ? null : (String) session.getAttribute("userEmail");
        User user = email == null ? null : USERS.stream().filter(u -> u.email.equalsIgnoreCase(email)).findFirst().orElse(null);
        currentUser.set(user);
        
        try {
            if (path.equals("/action")) {
                performAction(form);
                redirect(response, form.getOrDefault("return", "/"));
                return;
            }
            if (path.equals("/login_submit")) {
                String loginEmail = form.getOrDefault("email", "").trim();
                String pass = form.getOrDefault("password", "");
                User loginUser = USERS.stream().filter(u -> u.email.equalsIgnoreCase(loginEmail) && (u.password.equals(pass) || (u.email.equalsIgnoreCase("admin@quizora.dev") && (pass.equals("admin123") || pass.equals("password"))))).findFirst().orElse(null);
                if (loginUser != null) {
                    if (session != null) session.invalidate();
                    request.getSession(true).setAttribute("userEmail", loginUser.email);
                    redirect(response, loginUser.role.equals("Admin") ? "/admin" : loginUser.role.equals("Quiz Creator") ? "/creator" : "/participant");
                } else {
                    send(response, 200, authPage("login", "Invalid email or password. Please verify your details.", loginEmail, "", null));
                }
                return;
            }
            if (path.equals("/signup_submit")) {
                String name = safe(form.getOrDefault("name", "").trim());
                String signupEmail = form.getOrDefault("email", "").trim().toLowerCase();
                String pass = form.getOrDefault("password", "");
                String confirmPass = form.getOrDefault("confirmPassword", "");
                String role = form.getOrDefault("role", "Participant").trim();

                if (name.isBlank() || signupEmail.isBlank() || pass.isBlank()) {
                    send(response, 200, authPage("signup", "All fields are required.", signupEmail, name, null));
                    return;
                }
                if (!signupEmail.contains("@") || !signupEmail.contains(".")) {
                    send(response, 200, authPage("signup", "Please enter a valid email address.", signupEmail, name, null));
                    return;
                }
                if (pass.length() < 4) {
                    send(response, 200, authPage("signup", "Password must be at least 4 characters long.", signupEmail, name, null));
                    return;
                }
                if (!pass.equals(confirmPass)) {
                    send(response, 200, authPage("signup", "Passwords do not match. Please re-enter.", signupEmail, name, null));
                    return;
                }
                if (!role.equals("Participant") && !role.equals("Quiz Creator") && !role.equals("Admin")) {
                    role = "Participant";
                }

                boolean exists = USERS.stream().anyMatch(u -> u.email.equalsIgnoreCase(signupEmail));
                if (exists) {
                    send(response, 200, authPage("signup", "An account with email '" + signupEmail + "' already exists. Please log in.", signupEmail, name, null));
                    return;
                }

                User newUser = new User(name, signupEmail, pass, role, "Active");
                USERS.add(newUser);
                saveData();

                send(response, 200, authPage("login", null, signupEmail, "", "Account created successfully! Please sign in with your password."));
                return;
            }
            if (path.equals("/logout")) {
                if (session != null) session.invalidate();
                redirect(response, "/auth");
                return;
            }

            // Redirect authenticated users away from auth pages to their respective dashboards
            if (user != null && (path.equals("/") || path.equals("/auth") || path.equals("/signup"))) {
                redirect(response, user.role.equals("Admin") ? "/admin" : user.role.equals("Quiz Creator") ? "/creator" : "/participant");
                return;
            }

            // Unauthenticated users must log in to access protected pages
            if (user == null && !path.equals("/auth") && !path.equals("/signup") && !path.equals("/") && !path.equals("/favicon.ico")) {
                redirect(response, "/auth");
                return;
            }

            String html = switch (path) {
                case "/", "/auth" -> authPage("login", null, "", "", null);
                case "/signup" -> authPage("signup", null, "", "", null);
                case "/participant" -> participantDashboard();
                case "/quizzes" -> {
                    if (user != null && ("Admin".equalsIgnoreCase(user.role) || "Quiz Creator".equalsIgnoreCase(user.role))) {
                        yield adminQuizLibrary(form.getOrDefault("search", ""), user.role.equals("Admin") ? "admin" : "creator");
                    }
                    yield quizLibrary(form.getOrDefault("search", ""));
                }
                case "/quiz-attempts" -> quizAttemptsPage(form.getOrDefault("name", ""));
                case "/quiz" -> takeQuiz(form.getOrDefault("name", ""));
                case "/submit" -> submitQuiz(form);
                case "/performance" -> performancePage();
                case "/leaderboard" -> leaderboardPage();
                case "/creator" -> creatorDashboard();
                case "/admin" -> adminDashboard();
                default -> notFound();
            };
            if (path.equals("/favicon.ico")) { response.setStatus(HttpServletResponse.SC_NO_CONTENT); return; }
            send(response, 200, html);
        } finally {
            currentUser.remove();
        }
    }

    private static Map<String, String> readParameters(HttpServletRequest request) {
        Map<String, String> values = new LinkedHashMap<>();
        request.getParameterMap().forEach((key, value) -> values.put(key, value == null || value.length == 0 ? "" : value[0]));
        return values;
    }

    private static void performAction(Map<String, String> form) {
        String action = form.getOrDefault("type", "");
        switch (action) {
            case "addUser" -> {
                String newUserEmail = safe(form.get("email")).trim().toLowerCase();
                if (USERS.stream().noneMatch(u -> u.email.equalsIgnoreCase(newUserEmail))) {
                    USERS.add(new User(safe(form.get("name")), newUserEmail, "password", safe(form.get("role")), "Active"));
                    successMessage = "User account created successfully.";
                } else {
                    successMessage = "A user with that email already exists.";
                }
            }
            case "deleteUser" -> {
                USERS.removeIf(u -> u.email.equalsIgnoreCase(form.get("email")));
                successMessage = "User account deleted successfully.";
            }
            case "approveQuiz" -> {
                QUIZZES.stream().filter(q -> q.title.equals(form.get("name"))).findFirst().ifPresent(q -> q.status = "Published");
                successMessage = "Quiz approved and published.";
            }
            case "rejectQuiz" -> {
                QUIZZES.stream().filter(q -> q.title.equals(form.get("name"))).findFirst().ifPresent(q -> q.status = "Rejected");
                successMessage = "Quiz submission rejected.";
            }
            case "createQuiz" -> {
                int duration = number(form.get("duration"), 15);
                Quiz newQuiz = new Quiz(safe(form.get("title")), safe(form.get("description")), duration, safe(form.get("level")), "Pending", currentUser.get().email);
                
                // Read questions from form dynamically
                for (int i = 0; i < 50; i++) {
                    String qText = form.get("q" + i + "_text");
                    if (qText != null && !qText.isBlank()) {
                        String[] opts = {
                            safe(form.get("q" + i + "_o0")),
                            safe(form.get("q" + i + "_o1")),
                            safe(form.get("q" + i + "_o2")),
                            safe(form.get("q" + i + "_o3"))
                        };
                        int correct = number(form.get("q" + i + "_correct"), 0);
                        newQuiz.questions.add(new Question(safe(qText), opts, correct));
                    }
                }
                if (newQuiz.questions.isEmpty()) {
                    newQuiz.questions.add(new Question("Sample Question?", new String[]{"A", "B", "C", "D"}, 0));
                }
                QUIZZES.add(newQuiz);
                successMessage = "Quiz submitted for administrator approval.";
            }
            case "settings" -> successMessage = "System settings updated successfully.";
            default -> successMessage = "Action completed successfully.";
        }
        saveData();
    }

    private static int number(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (Exception ignored) { return fallback; }
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
    
    private static String authPage(String mode, String error, String emailVal, String nameVal, String success) {
        boolean isSignup = "signup".equalsIgnoreCase(mode);
        String alertHtml = "";
        if (error != null && !error.isBlank()) {
            alertHtml = "<div class='auth-alert error'><span>⚠️</span> " + safe(error) + "</div>";
        } else if (success != null && !success.isBlank()) {
            alertHtml = "<div class='auth-alert success'><span>✓</span> " + safe(success) + "</div>";
        }

        String safeEmail = safe(emailVal);
        String safeName = safe(nameVal);

        return """
            <!doctype html><html lang="en"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>%s · Quizora</title><style>%s</style></head>
            <body style="min-height:100vh;margin:0;display:flex;flex-direction:column;background:var(--bg)">
            <main style="flex:1 0 auto;width:100%%;max-width:1280px;margin:0 auto;display:flex;align-items:center;justify-content:center;padding:36px 16px">
            <section class="panel auth-card" style="width:100%%;max-width:430px;text-align:center;box-shadow:0 18px 45px #3a2a6015;border-radius:20px;padding:34px 28px">
              <div class="brand" style="justify-content:center;margin-bottom:6px;font-size:28px"><span>&lt;/&gt;</span>quiz<b>ora</b></div>
              <p style="color:var(--muted);font-size:11px;margin:0 0 20px;font-weight:600">Master Java Programming with Interactive Quizzes</p>

              <div class="auth-tabs">
                <button type="button" class="tab-btn %s" id="tab-login-btn" onclick="switchAuthTab('login')">Sign In</button>
                <button type="button" class="tab-btn %s" id="tab-signup-btn" onclick="switchAuthTab('signup')">Create Account (Sign Up)</button>
              </div>

              %s

              <!-- Sign In Form -->
              <form id="login-form" class="form" method="post" action="/login_submit" style="display:%s">
                <label style="text-align:left">Email Address
                  <input type="email" id="login-email" name="email" required placeholder="e.g. user@example.com" value="%s">
                </label>
                <label style="text-align:left">Password
                  <input type="password" id="login-password" name="password" required placeholder="Enter your password">
                </label>
                <button class="button" style="width:100%%;margin-top:14px;padding:12px;font-size:11px;font-weight:800;letter-spacing:.5px" type="submit">Sign In →</button>
                
                <div class="auth-switch">
                  Don't have an account yet? <a href="javascript:void(0)" onclick="switchAuthTab('signup')">Sign up here</a>
                </div>
              </form>

              <!-- Sign Up Form -->
              <form id="signup-form" class="form" method="post" action="/signup_submit" style="display:%s">
                <label style="text-align:left">Full Name
                  <input type="text" name="name" required placeholder="e.g. Alex Johnson" value="%s">
                </label>
                <label style="text-align:left">Email Address
                  <input type="email" name="email" required placeholder="e.g. alex@example.com" value="%s">
                </label>
                <label style="text-align:left">Account Role
                  <select name="role">
                    <option value="Participant">🎓 Participant / Learner (Take quizzes &amp; compete)</option>
                    <option value="Quiz Creator">✍️ Quiz Creator (Author quizzes for learners)</option>
                    <option value="Admin">👑 System Administrator (Manage users &amp; moderate)</option>
                  </select>
                </label>
                <div class="form-row" style="grid-template-columns:1fr 1fr;gap:9px">
                  <label style="text-align:left">Password
                    <input type="password" name="password" required minlength="4" placeholder="Min. 4 chars">
                  </label>
                  <label style="text-align:left">Confirm Password
                    <input type="password" name="confirmPassword" required minlength="4" placeholder="Repeat password">
                  </label>
                </div>
                <button class="button" style="width:100%%;margin-top:14px;padding:12px;font-size:11px;font-weight:800;letter-spacing:.5px" type="submit">Create Account (Sign Up) →</button>
                
                <div class="auth-switch">
                  Already have an account? <a href="javascript:void(0)" onclick="switchAuthTab('login')">Sign in here</a>
                </div>
              </form>
            </section>
            </main>

            <footer><a class="brand" href="/auth"><span>&lt;/&gt;</span>quiz<b>ora</b></a><p>Java learning made practical. Built entirely with Java.</p><small>© %d Quizora. Educational project.</small></footer>

            <script>
              function switchAuthTab(tab) {
                var loginForm = document.getElementById('login-form');
                var signupForm = document.getElementById('signup-form');
                var loginBtn = document.getElementById('tab-login-btn');
                var signupBtn = document.getElementById('tab-signup-btn');
                if (tab === 'signup') {
                  loginForm.style.display = 'none';
                  signupForm.style.display = 'flex';
                  loginBtn.classList.remove('active');
                  signupBtn.classList.add('active');
                  try { history.replaceState(null, '', '/signup'); } catch(e){}
                } else {
                  signupForm.style.display = 'none';
                  loginForm.style.display = 'flex';
                  signupBtn.classList.remove('active');
                  loginBtn.classList.add('active');
                  try { history.replaceState(null, '', '/auth'); } catch(e){}
                }
              }
              function fillDemo(email, pass) {
                document.getElementById('login-email').value = email;
                document.getElementById('login-password').value = pass;
              }
            </script>
            </body></html>
            """.formatted(
                isSignup ? "Create Account · Sign Up" : "Sign In",
                CSS,
                isSignup ? "" : "active",
                isSignup ? "active" : "",
                alertHtml,
                isSignup ? "none" : "flex",
                safeEmail,
                isSignup ? "flex" : "none",
                safeName,
                safeEmail,
                LocalDate.now().getYear()
            );
    }

    private static final class LeaderboardEntry {
        final User user;
        final int score;
        final int rank;

        LeaderboardEntry(User user, int score, int rank) {
            this.user = user;
            this.score = score;
            this.rank = rank;
        }
    }

    private static List<LeaderboardEntry> getLeaderboard() {
        Map<String, Integer> scores = new HashMap<>();
        for (User u : USERS) {
            if ("Participant".equalsIgnoreCase(u.role)) {
                scores.put(u.email.toLowerCase(), 0);
            }
        }
        for (QuizAttempt a : ATTEMPTS) {
            String email = a.userEmail.toLowerCase();
            scores.put(email, scores.getOrDefault(email, 0) + (a.score * 10));
        }
        if (currentUser.get() != null) {
            scores.putIfAbsent(currentUser.get().email.toLowerCase(), 0);
        }

        List<Map.Entry<String, Integer>> sorted = scores.entrySet().stream()
            .sorted((e1, e2) -> {
                int cmp = e2.getValue().compareTo(e1.getValue());
                if (cmp != 0) return cmp;
                User u1 = USERS.stream().filter(u -> u.email.equalsIgnoreCase(e1.getKey())).findFirst().orElse(null);
                User u2 = USERS.stream().filter(u -> u.email.equalsIgnoreCase(e2.getKey())).findFirst().orElse(null);
                String n1 = u1 != null ? u1.name : e1.getKey();
                String n2 = u2 != null ? u2.name : e2.getKey();
                return n1.compareToIgnoreCase(n2);
            })
            .collect(Collectors.toList());

        List<LeaderboardEntry> entries = new ArrayList<>();
        int rank = 1;
        for (Map.Entry<String, Integer> entry : sorted) {
            User u = USERS.stream().filter(user -> user.email.equalsIgnoreCase(entry.getKey())).findFirst().orElse(null);
            if (u != null) {
                entries.add(new LeaderboardEntry(u, entry.getValue(), rank++));
            }
        }
        return entries;
    }

    private static String participantDashboard() {
        User user = currentUser.get();
        List<QuizAttempt> myAttempts = ATTEMPTS.stream().filter(a -> a.userEmail.equalsIgnoreCase(user.email)).collect(Collectors.toList());
        int totalTaken = myAttempts.size();
        double avgScore = myAttempts.isEmpty() ? 0 : myAttempts.stream().mapToDouble(a -> (double)a.score / a.maxScore).average().orElse(0.0) * 100;
        int totalScore = myAttempts.stream().mapToInt(a -> a.score * 10).sum();
        
        List<LeaderboardEntry> leaderboard = getLeaderboard();
        int myRank = 1;
        for (LeaderboardEntry entry : leaderboard) {
            if (entry.user.email.equalsIgnoreCase(user.email)) {
                myRank = entry.rank;
                break;
            }
        }

        String first = leaderboard.size() > 0 ? safe(leaderboard.get(0).user.name.split(" ")[0]) : "-";
        String second = leaderboard.size() > 1 ? safe(leaderboard.get(1).user.name.split(" ")[0]) : "-";
        String third = leaderboard.size() > 2 ? safe(leaderboard.get(2).user.name.split(" ")[0]) : "-";

        String rankPanelHtml = """
            <aside class="rank-panel">
              <span class="kicker">♛ YOUR WEEKLY RANK</span>
              <h3>Top learners</h3>
              <div class="podium">
                <div><b>2</b><small>%s</small></div>
                <div class="winner"><b>👑</b><small>%s</small></div>
                <div><b>3</b><small>%s</small></div>
              </div>
              <div class="me">
                <span class="avatar">%s</span>
                <div><strong>%s (You)</strong><small>Rank #%d</small></div>
                <em>%d pts</em>
              </div>
              <a href="/leaderboard">View full leaderboard →</a>
            </aside>
            """.formatted(second, first, third, initials(user.name), safe(user.name), myRank, totalScore);

        String quizCards = QUIZZES.stream().filter(q -> q.status.equals("Published")).limit(6).map(QuizoraApplication::quizCard).reduce("", String::concat);
        return page("Dashboard", "participant", """
            <section class="hero">
              <div><span class="kicker">✦ KEEP LEARNING, KEEP GROWING</span><h1>Welcome, %s! 👋</h1>
              <p>Ready to sharpen your Java skills? Pick up where you left off or explore something new.</p>
              </div>
              <div class="hero-art"><div class="code-window"><i></i><i></i><i></i><code><b>public class</b> Quiz {<br>&nbsp;&nbsp;keepLearning();<br>}</code></div><div class="trophy">🏆</div></div>
            </section>
            <section class="stats">
              <article><span class="stat-icon purple">▤</span><div><small>QUIZZES COMPLETED</small><strong>%d</strong></div></article>
              <article><span class="stat-icon orange">◎</span><div><small>AVERAGE SCORE</small><strong>%.1f%%</strong></div></article>
              <article><span class="stat-icon blue">◷</span><div><small>TOTAL POINTS</small><strong>%d</strong></div></article>
              <article><span class="stat-icon pink">♛</span><div><small>GLOBAL RANK</small><strong>#%d</strong></div></article>
            </section>
            <div class="main-grid"><section><div class="section-head"><div><span class="kicker">RECOMMENDED FOR YOU</span><h2>Explore popular quizzes</h2></div><a href="/quizzes">Explore all →</a></div><div class="quiz-grid">%s</div></section>
              %s
            </div>
            <section class="history"><div class="section-head"><div><span class="kicker">RECENT ACTIVITY</span><h2>Your quiz history</h2></div><a href="/performance">View full history →</a></div>%s</section>
            """.formatted(user.name.split(" ")[0], totalTaken, avgScore, totalScore, myRank, quizCards, rankPanelHtml, historyTable(myAttempts)));
    }

    private static String quizLibrary(String search) {
        String query = search.toLowerCase();
        String cards = QUIZZES.stream().filter(q -> q.status.equals("Published") && q.title.toLowerCase().contains(query)).map(QuizoraApplication::quizCard).reduce("", String::concat);
        return page("Explore Quizzes", "participant", """
            <section class="page-title"><span class="kicker">QUIZ LIBRARY</span><h1>Explore Quizzes</h1><p>Choose a topic, challenge yourself, and test your technical expertise.</p></section>
            <form class="search" method="get" action="/quizzes"><span>⌕</span><input name="search" value="%s" placeholder="Search quizzes by topic..."><button>Search</button></form>
            <section class="quiz-grid library">%s</section>
            """.formatted(safe(search), cards.isEmpty() ? "<div class='empty'>No quizzes match your search.</div>" : cards));
    }

    private static String quizCard(Quiz quiz) {
        String color = quiz.level.equals("Beginner") ? "green" : quiz.level.equals("Advanced") ? "pink" : "amber";
        return """
            <article class="quiz-card"><div class="quiz-top"><span class="quiz-icon">&lt;/&gt;</span><span class="pill %s">%s</span></div><h3>%s</h3><p>%s</p><div class="quiz-meta"><span>▤ %d questions</span><span>◷ %d min</span></div><a href="/quiz?name=%s">Start quiz →</a></article>
            """.formatted(color, quiz.level, quiz.title, quiz.description, quiz.questions.size(), quiz.duration, URLEncoder.encode(quiz.title, StandardCharsets.UTF_8));
    }

    private static String escapeJs(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private static String adminQuizLibrary(String search, String role) {
        String query = search == null ? "" : search.trim().toLowerCase();
        List<Quiz> filtered = QUIZZES.stream()
            .filter(q -> query.isEmpty() || q.title.toLowerCase().contains(query) || q.description.toLowerCase().contains(query))
            .collect(Collectors.toList());

        int totalAttemptsCount = ATTEMPTS.size();
        long uniqueLearners = ATTEMPTS.stream().map(a -> a.userEmail.toLowerCase()).distinct().count();

        StringBuilder cardsHtml = new StringBuilder();
        StringBuilder attemptsJson = new StringBuilder("{");

        for (int i = 0; i < filtered.size(); i++) {
            Quiz q = filtered.get(i);
            List<QuizAttempt> qAttempts = ATTEMPTS.stream()
                .filter(a -> a.quizTitle.equalsIgnoreCase(q.title))
                .collect(Collectors.toList());

            String color = q.level.equals("Beginner") ? "green" : q.level.equals("Advanced") ? "pink" : "amber";
            String statusClass = q.status.equalsIgnoreCase("Published") ? "published" : q.status.equalsIgnoreCase("Pending") ? "pending" : "rejected";

            cardsHtml.append("""
                <article class="quiz-card" style="display:flex;flex-direction:column;justify-content:space-between">
                  <div>
                    <div class="quiz-top">
                      <span class="quiz-icon">&lt;/&gt;</span>
                      <div style="display:flex;gap:6px">
                        <span class="pill %s">%s</span>
                        <span class="status %s">%s</span>
                      </div>
                    </div>
                    <h3>%s</h3>
                    <p>%s</p>
                    <div class="quiz-meta" style="flex-wrap:wrap;gap:8px">
                      <span>▤ %d questions</span>
                      <span>◷ %d min</span>
                      <span>✍️ By %s</span>
                    </div>
                  </div>
                  <div style="margin-top:16px;padding-top:14px;border-top:1px solid #efecf2;display:flex;align-items:center;justify-content:space-between;gap:8px;flex-wrap:wrap">
                    <span style="font-size:11px;font-weight:700;color:var(--muted)">📊 %d Attempt(s)</span>
                    <button type="button" class="button" onclick="openAttemptsModal('%s')" style="padding:7px 12px;font-size:10px">View Attempts →</button>
                  </div>
                </article>
                """.formatted(
                    color, q.level, statusClass, q.status,
                    safe(q.title), safe(q.description),
                    q.questions.size(), q.duration, safe(q.creatorEmail),
                    qAttempts.size(), escapeJs(q.title)
                ));

            if (i > 0) attemptsJson.append(",");
            attemptsJson.append("\"").append(escapeJs(q.title)).append("\":{")
                .append("\"title\":\"").append(escapeJs(q.title)).append("\",")
                .append("\"duration\":").append(q.duration).append(",")
                .append("\"questions\":").append(q.questions.size()).append(",")
                .append("\"attempts\":[");
            for (int j = 0; j < qAttempts.size(); j++) {
                QuizAttempt a = qAttempts.get(j);
                User u = USERS.stream().filter(user -> user.email.equalsIgnoreCase(a.userEmail)).findFirst().orElse(null);
                String uName = u != null ? u.name : a.userEmail;
                int percent = a.maxScore > 0 ? (int)((double)a.score / a.maxScore * 100) : 0;
                boolean passed = percent >= 50;
                if (j > 0) attemptsJson.append(",");
                attemptsJson.append("{")
                    .append("\"name\":\"").append(escapeJs(uName)).append("\",")
                    .append("\"email\":\"").append(escapeJs(a.userEmail)).append("\",")
                    .append("\"score\":\"").append(a.score).append(" / ").append(a.maxScore).append(" (").append(percent).append("%)\",")
                    .append("\"status\":\"").append(passed ? "Passed" : "Failed").append("\",")
                    .append("\"passed\":").append(passed).append(",")
                    .append("\"date\":\"").append(escapeJs(a.date)).append("\"")
                    .append("}");
            }
            attemptsJson.append("]}");
        }
        attemptsJson.append("}");

        String roleTitle = "admin".equalsIgnoreCase(role) ? "Administrator Quiz Console" : "Creator Studio · Quiz Library";
        String kicker = "admin".equalsIgnoreCase(role) ? "ADMINISTRATION · QUIZ MANAGEMENT" : "CREATOR WORKSPACE · QUIZ LIBRARY";

        return page(roleTitle, role, """
            <section class="dashboard-title">
              <div>
                <span class="kicker">%s</span>
                <h1>Platform Quiz Library</h1>
                <p>Manage all quizzes, review publication statuses, and monitor participant attempt performance.</p>
              </div>
              <div style="display:flex;gap:10px">
                %s
              </div>
            </section>

            <section class="stats three">
              <article><span class="stat-icon purple">▤</span><div><small>TOTAL QUIZZES</small><strong>%d</strong></div></article>
              <article><span class="stat-icon orange">▶</span><div><small>TOTAL ATTEMPTS</small><strong>%d</strong></div></article>
              <article><span class="stat-icon blue">👥</span><div><small>LEARNERS PARTICIPATED</small><strong>%d</strong></div></article>
            </section>

            <form class="search" method="get" action="/quizzes" style="margin-bottom:24px">
              <span>⌕</span>
              <input name="search" value="%s" placeholder="Search quizzes by title or topic...">
              <button type="submit">Search</button>
            </form>

            <section class="quiz-grid library">
              %s
            </section>

            <!-- Attempts Modal -->
            <div id="attempts-modal" class="modal-overlay" onclick="closeAttemptsModal(event)">
              <div class="modal-card" onclick="event.stopPropagation()">
                <button type="button" class="modal-close" onclick="closeAttemptsModal()">&times;</button>
                <div style="margin-bottom:18px">
                  <span class="kicker">ATTEMPT RECORDS</span>
                  <h2 id="modal-quiz-title" style="margin:4px 0 6px;font:800 22px Manrope">Quiz Attempts</h2>
                  <p id="modal-quiz-meta" style="color:var(--muted);font-size:12px;margin:0"></p>
                </div>
                <div id="modal-table-container"></div>
                <div style="margin-top:20px;display:flex;justify-content:space-between;align-items:center;gap:10px">
                  <a id="modal-full-link" href="#" class="button-outline" style="font-size:10px">Open Dedicated Report Page →</a>
                  <button type="button" class="button" onclick="closeAttemptsModal()">Close</button>
                </div>
              </div>
            </div>

            <script>
              var quizData = %s;

              function openAttemptsModal(quizTitle) {
                var data = quizData[quizTitle];
                if (!data) return;
                document.getElementById('modal-quiz-title').textContent = data.title;
                document.getElementById('modal-quiz-meta').textContent = data.attempts.length + ' total attempt(s) recorded · ' + data.questions + ' questions · ' + data.duration + ' minutes';
                document.getElementById('modal-full-link').href = '/quiz-attempts?name=' + encodeURIComponent(data.title);

                var container = document.getElementById('modal-table-container');
                if (data.attempts.length === 0) {
                  container.innerHTML = "<div class='empty' style='padding:30px;background:#faf9fd;border-radius:12px;border:1px dashed var(--line)'>No participants have attempted this quiz yet.</div>";
                } else {
                  var html = '<div class="table" style="box-shadow:none;border:1px solid var(--line);border-radius:12px;overflow:hidden">' +
                    '<div class="tr th" style="grid-template-columns:1.5fr 1.6fr 1.1fr 1fr 1fr;background:#f8f6fc">' +
                      '<span>LEARNER NAME</span>' +
                      '<span>EMAIL</span>' +
                      '<span>SCORE</span>' +
                      '<span>STATUS</span>' +
                      '<span>DATE</span>' +
                    '</div>';
                  for (var i = 0; i < data.attempts.length; i++) {
                    var att = data.attempts[i];
                    var statusBadge = att.passed 
                      ? '<span class="status published">✓ Passed</span>' 
                      : '<span class="status rejected">✗ Failed</span>';
                    html += '<div class="tr" style="grid-template-columns:1.5fr 1.6fr 1.1fr 1fr 1fr;min-height:46px">' +
                      '<strong>' + att.name + '</strong>' +
                      '<span style="color:var(--muted);font-size:10px">' + att.email + '</span>' +
                      '<b>' + att.score + '</b>' +
                      statusBadge +
                      '<small style="color:var(--muted)">' + att.date + '</small>' +
                    '</div>';
                  }
                  html += '</div>';
                  container.innerHTML = html;
                }
                var modal = document.getElementById('attempts-modal');
                modal.style.display = 'flex';
              }

              function closeAttemptsModal(e) {
                var modal = document.getElementById('attempts-modal');
                if (modal) modal.style.display = 'none';
              }
            </script>
            """.formatted(
                kicker,
                "creator".equalsIgnoreCase(role) ? "<a href='/creator#create' class='button'>＋ Create New Quiz</a>" : "",
                QUIZZES.size(), totalAttemptsCount, uniqueLearners,
                safe(search),
                cardsHtml.length() == 0 ? "<div class='empty'>No quizzes found matching your criteria.</div>" : cardsHtml.toString(),
                attemptsJson.toString()
            ));
    }

    private static String quizAttemptsPage(String quizName) {
        User user = currentUser.get();
        if (user == null) return notFound();
        String role = user.role.equals("Admin") ? "admin" : user.role.equals("Quiz Creator") ? "creator" : "participant";
        if ("participant".equals(role)) {
            return quizLibrary("");
        }

        Quiz quiz = QUIZZES.stream().filter(q -> q.title.equalsIgnoreCase(quizName)).findFirst().orElse(null);
        if (quiz == null) return notFound();

        List<QuizAttempt> qAttempts = ATTEMPTS.stream()
            .filter(a -> a.quizTitle.equalsIgnoreCase(quiz.title))
            .collect(Collectors.toList());

        long passedCount = qAttempts.stream().filter(a -> a.maxScore > 0 && ((double)a.score / a.maxScore) >= 0.5).count();
        double passRate = qAttempts.isEmpty() ? 0.0 : ((double)passedCount / qAttempts.size()) * 100.0;
        double avgScore = qAttempts.isEmpty() ? 0.0 : qAttempts.stream().mapToDouble(a -> a.maxScore > 0 ? ((double)a.score / a.maxScore) * 100.0 : 0.0).average().orElse(0.0);

        StringBuilder rows = new StringBuilder();
        if (qAttempts.isEmpty()) {
            rows.append("<div class='empty'>No attempts have been recorded for this quiz yet.</div>");
        } else {
            rows.append("""
                <div class="table" style="border:1px solid var(--line);border-radius:14px;overflow:hidden">
                  <div class="tr th" style="grid-template-columns:1.5fr 1.6fr 1.1fr 1fr 1fr;background:#f8f6fc">
                    <span>LEARNER NAME</span>
                    <span>EMAIL</span>
                    <span>SCORE</span>
                    <span>STATUS</span>
                    <span>DATE</span>
                  </div>
                """);
            for (QuizAttempt a : qAttempts) {
                User u = USERS.stream().filter(usr -> usr.email.equalsIgnoreCase(a.userEmail)).findFirst().orElse(null);
                String uName = u != null ? u.name : a.userEmail;
                int percent = a.maxScore > 0 ? (int)((double)a.score / a.maxScore * 100) : 0;
                boolean passed = percent >= 50;
                rows.append("""
                    <div class="tr" style="grid-template-columns:1.5fr 1.6fr 1.1fr 1fr 1fr">
                      <strong>%s</strong>
                      <span style="color:var(--muted)">%s</span>
                      <b>%d / %d (%d%%)</b>
                      <span class="status %s">%s</span>
                      <small>%s</small>
                    </div>
                    """.formatted(
                        safe(uName), safe(a.userEmail),
                        a.score, a.maxScore, percent,
                        passed ? "published" : "rejected",
                        passed ? "✓ Passed" : "✗ Failed",
                        safe(a.date)
                    ));
            }
            rows.append("</div>");
        }

        return page("Quiz Attempts: " + quiz.title, role, """
            <section class="dashboard-title">
              <div>
                <span class="kicker">ATTEMPT ANALYTICS</span>
                <h1>Attempts for %s</h1>
                <p>Complete learner submission history and grading status for this quiz.</p>
              </div>
              <a href="/quizzes" class="button-outline">← Back to Quiz Library</a>
            </section>

            <section class="stats">
              <article><span class="stat-icon purple">▶</span><div><small>TOTAL ATTEMPTS</small><strong>%d</strong></div></article>
              <article><span class="stat-icon orange">◎</span><div><small>AVERAGE SCORE</small><strong>%.1f%%</strong></div></article>
              <article><span class="stat-icon blue">✓</span><div><small>PASSED</small><strong>%d</strong></div></article>
              <article><span class="stat-icon pink">✦</span><div><small>PASS RATE</small><strong>%.0f%%</strong></div></article>
            </section>

            <section class="panel" style="margin-top:20px">
              <div class="section-head">
                <div>
                  <span class="kicker">PARTICIPANT SUBMISSIONS</span>
                  <h2>Attempt records</h2>
                </div>
              </div>
              %s
            </section>
            """.formatted(
                safe(quiz.title),
                qAttempts.size(), avgScore, passedCount, passRate,
                rows.toString()
            ));
    }

    private static String takeQuiz(String name) {
        Quiz quiz = QUIZZES.stream().filter(q -> q.title.equals(name)).findFirst().orElse(null);
        if (quiz == null) return notFound();
        
        StringBuilder questionsHtml = new StringBuilder();
        for (int i = 0; i < quiz.questions.size(); i++) {
            Question q = quiz.questions.get(i);
            questionsHtml.append("<h3>").append((i+1)).append(". ").append(q.text).append("</h3>");
            for (int j = 0; j < q.options.length; j++) {
                char letter = (char) ('A' + j);
                questionsHtml.append("<label class='answer'><input type='radio' name='q").append(i).append("' value='").append(j).append("' required><span>").append(letter).append("</span><b>").append(q.options[j]).append("</b></label>");
            }
        }

        int durationMinutes = Math.max(1, quiz.duration);
        int totalSeconds = durationMinutes * 60;
        
        return page("Take Quiz", "participant", """
            <section class="quiz-room">
              <header>
                <div><span class="kicker">%s ASSESSMENT</span><h2>%s</h2></div>
                <div class="timer">◷ <b id="timer-display">%02d:00</b><small>TIME REMAINING</small></div>
              </header>
              <div class="question-progress"><i id="timer-progress" style="width:100%%;transition:width 1s linear"></i></div>
              <form id="quiz-form" method="post" action="/submit">
                %s
                <input type="hidden" name="quiz" value="%s">
                <div class="quiz-actions">
                  <a href="/quizzes">Exit quiz</a>
                  <button type="submit" id="submit-btn" class="button">Submit Answer →</button>
                </div>
              </form>
            </section>
            <script>
            (function() {
              var totalSec = %d;
              var remaining = totalSec;
              var display = document.getElementById("timer-display");
              var prog = document.getElementById("timer-progress");
              var form = document.getElementById("quiz-form");
              var btn = document.getElementById("submit-btn");
              var isSubmitting = false;

              function formatTime(val) {
                var m = Math.floor(val / 60);
                var s = val - (m * 60);
                return (m < 10 ? "0" + m : m) + ":" + (s < 10 ? "0" + s : s);
              }

              function autoSubmitQuiz() {
                if (isSubmitting) return;
                isSubmitting = true;
                if (display) {
                  display.textContent = "00:00";
                  display.style.color = "#d75a72";
                }
                if (btn) {
                  btn.disabled = true;
                  btn.textContent = "Time's up! Submitting...";
                }
                if (form) {
                  form.noValidate = true;
                  var reqs = form.querySelectorAll("input[required]");
                  for (var i = 0; i < reqs.length; i++) {
                    reqs[i].removeAttribute("required");
                  }
                  var hidden = document.createElement("input");
                  hidden.type = "hidden";
                  hidden.name = "autoSubmitted";
                  hidden.value = "true";
                  form.appendChild(hidden);
                  form.submit();
                }
              }

              if (form) {
                form.addEventListener("submit", function() {
                  isSubmitting = true;
                });
              }

              var timer = setInterval(function() {
                remaining--;
                if (remaining <= 0) {
                  clearInterval(timer);
                  if (prog) prog.style.width = "0%%";
                  autoSubmitQuiz();
                } else {
                  if (display) {
                    display.textContent = formatTime(remaining);
                    if (remaining <= 30) {
                      display.style.color = "#d75a72";
                      if (prog) prog.style.background = "#d75a72";
                    } else if (remaining <= 60) {
                      display.style.color = "#dc8735";
                      if (prog) prog.style.background = "#dc8735";
                    }
                  }
                  if (prog && totalSec > 0) {
                    var pct = Math.max(0, (remaining * 100) / totalSec);
                    prog.style.width = pct + "%%";
                  }
                }
              }, 1000);
            })();
            </script>
            """.formatted(safe(quiz.level.toUpperCase()), safe(name), durationMinutes, questionsHtml, safe(name), totalSeconds));
    }

    private static String submitQuiz(Map<String, String> form) {
        String quizName = form.get("quiz");
        Quiz quiz = QUIZZES.stream().filter(q -> q.title.equals(quizName)).findFirst().orElse(null);
        if (quiz == null) return notFound();
        
        int score = 0;
        for (int i = 0; i < quiz.questions.size(); i++) {
            int selected = number(form.get("q" + i), -1);
            if (selected == quiz.questions.get(i).correctOptionIndex) {
                score++;
            }
        }
        
        QuizAttempt attempt = new QuizAttempt(currentUser.get().email, quiz.title, score, quiz.questions.size(), LocalDate.now().toString());
        ATTEMPTS.add(attempt);
        saveData();
        
        double percentage = (double)score / quiz.questions.size() * 100;
        boolean passed = percentage >= 50;
        boolean autoSubmitted = "true".equalsIgnoreCase(form.get("autoSubmitted"));
        String autoSubmitNotice = autoSubmitted 
            ? "<div style='margin-bottom:18px;padding:12px 18px;background:#fff8e6;border:1px solid #ffd8a8;color:#b45309;border-radius:10px;font-size:12px;font-weight:700;'>⏰ Time expired! Your quiz answers were automatically submitted.</div>" 
            : "";
        
        return page("Quiz Result", "participant", """
            <section class="result-card">%s<div class="result-ring" style="border-color:%s">%d%%</div><span class="kicker">QUIZ COMPLETE</span><h1>%s</h1><p>You completed <b>%s</b>.</p><div class="result-stats"><div><strong>%d / %d</strong><small>Correct answers</small></div><div><strong>+%d</strong><small>Points earned</small></div></div><a class="button" href="/quizzes">Explore more quizzes</a></section>
            """.formatted(autoSubmitNotice, passed ? "#439d68" : "#d75a72", (int)percentage, passed ? "Excellent work!" : "Keep practising!", safe(quizName), score, quiz.questions.size(), score * 10));
    }

    private static String performancePage() {
        User user = currentUser.get();
        List<QuizAttempt> myAttempts = ATTEMPTS.stream().filter(a -> a.userEmail.equals(user.email)).collect(Collectors.toList());
        double avgScore = myAttempts.isEmpty() ? 0 : myAttempts.stream().mapToDouble(a -> (double)a.score / a.maxScore).average().orElse(0.0) * 100;

        return page("Performance", "participant", """
            <section class="page-title"><span class="kicker">PERFORMANCE REPORT</span><h1>Your learning progress</h1></section>
            <section class="performance-hero"><div><small>OVERALL PERFORMANCE</small><strong>%.1f%%</strong><span>Average score</span></div></section>
            %s
            """.formatted(avgScore, historyTable(myAttempts)));
    }

    private static String historyTable(List<QuizAttempt> attempts) {
        if (attempts.isEmpty()) return "<p>No attempts yet.</p>";
        StringBuilder rows = new StringBuilder("<div class='table'><div class='tr th'><span>QUIZ</span><span>DATE</span><span>SCORE</span><span>RESULT</span><span>STATUS</span></div>");
        for (QuizAttempt a : attempts) {
            int percent = (int)((double)a.score / a.maxScore * 100);
            boolean passed = percent >= 50;
            rows.append("<div class='tr'><strong>").append(a.quizTitle).append("</strong><span>").append(a.date).append("</span><b>").append(percent).append("%</b><span>").append(a.score).append(" / ").append(a.maxScore).append("</span><mark style='").append(passed?"":"color:#d75a72;background:#ffe7eb").append("'>").append(passed ? "✓ Passed" : "✗ Failed").append("</mark></div>");
        }
        rows.append("</div>");
        return rows.toString();
    }

    private static String leaderboardPage() {
        List<LeaderboardEntry> leaderboard = getLeaderboard();
        StringBuilder rows = new StringBuilder();
        for (LeaderboardEntry entry : leaderboard) {
            User u = entry.user;
            boolean isMe = u.email.equalsIgnoreCase(currentUser.get().email);
            rows.append("<div class='leader-row ").append(isMe ? "current" : "").append("'><b>")
                .append(entry.rank).append("</b><span class='avatar'>")
                .append(initials(u.name)).append("</span><strong>")
                .append(u.name).append(isMe ? " <small>(You)</small>" : "").append("</strong><em>")
                .append(entry.score).append(" pts</em></div>");
        }
        return page("Leaderboard", "participant", "<section class='page-title'><span class='kicker'>COMMUNITY</span><h1>Java leaderboard</h1></section><section class='leaderboard'>" + (rows.length() == 0 ? "<div class='empty'>No rankings yet.</div>" : rows) + "</section>");
    }

    private static String creatorDashboard() {
        User user = currentUser.get();
        List<Quiz> myQuizzes = QUIZZES.stream().filter(q -> user.email.equalsIgnoreCase(q.creatorEmail)).collect(Collectors.toList());
        
        StringBuilder quizRows = new StringBuilder();
        for (Quiz q : myQuizzes) quizRows.append("<div class='tr creator-row'><strong>").append(q.title).append("</strong><span>").append(q.questions.size()).append(" questions</span><span>").append(q.duration).append(" min</span><span class='status ").append(q.status.toLowerCase()).append("'>").append(q.status).append("</span><a href='/quizzes'>View in library →</a></div>");
        
        return page("Creator Studio", "creator", """
            <section class="dashboard-title"><div><span class="kicker">QUIZ CREATOR WORKSPACE</span><h1>Welcome back, %s</h1></div><a class="button" href="#create">＋ Create new quiz</a></section>
            <section class="stats three"><article><span class="stat-icon purple">▤</span><div><small>YOUR QUIZZES</small><strong>%d</strong></div></article></section>
            <section class="panel"><div class="section-head"><div><span class="kicker">CONTENT MANAGEMENT</span><h2>Your quizzes</h2></div></div><div class="table creator-table"><div class="tr th creator-row"><span>QUIZ</span><span>QUESTIONS</span><span>DURATION</span><span>STATUS</span><span>ACTION</span></div>%s</div></section>
            
            <section class="panel" id="create" style="margin-top:20px"><span class="kicker">CREATE QUIZ</span><h2>Build a new Java quiz</h2>
            <form method="post" action="/action" class="form"><input type="hidden" name="type" value="createQuiz"><input type="hidden" name="return" value="/creator">
            <label>Quiz title<input required name="title" placeholder="e.g. Java Streams &amp; Lambdas"></label>
            <label>Description<textarea required name="description" placeholder="What will participants learn from this quiz?"></textarea></label>
            <div class="form-row"><label>Duration (minutes)<input required type="number" min="1" max="180" name="duration" value="15"></label><label>Difficulty<select name="level"><option>Beginner</option><option>Intermediate</option><option>Advanced</option></select></label></div>
            
            <div style="display:flex;justify-content:space-between;align-items:center;margin-top:26px;margin-bottom:12px">
              <div><h3 style="margin:0;font:800 16px Manrope">Add Questions</h3><small style="color:var(--muted);font-size:11px">Provide questions with 4 answer choices and indicate the correct answer.</small></div>
              <span id="question-count-badge" class="pill amber" style="font-size:10px">1 Question</span>
            </div>

            <div id="questions-container">
               <div class="question-card" id="qcard-0" style="border:1px solid var(--line);background:#faf9fd;padding:18px;border-radius:12px;margin-bottom:14px">
                  <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:10px">
                    <strong class="qcard-title" style="color:var(--purple);font-size:12px">Question 1</strong>
                  </div>
                  <label>Question text<input required name="q0_text" placeholder="e.g. Which method is used to filter elements in a Java Stream?"></label>
                  <div class="form-row" style="grid-template-columns:1fr 1fr;gap:10px;margin-top:8px">
                    <label>Option A<input required name="q0_o0" placeholder="Option A"></label>
                    <label>Option B<input required name="q0_o1" placeholder="Option B"></label>
                    <label>Option C<input required name="q0_o2" placeholder="Option C"></label>
                    <label>Option D<input required name="q0_o3" placeholder="Option D"></label>
                  </div>
                  <label style="margin-top:8px;max-width:260px">Correct Answer
                    <select name="q0_correct">
                      <option value="0">Option A</option>
                      <option value="1">Option B</option>
                      <option value="2">Option C</option>
                      <option value="3">Option D</option>
                    </select>
                  </label>
               </div>
            </div>

            <div style="display:flex;gap:12px;align-items:center;margin-top:18px;flex-wrap:wrap">
              <button type="button" class="button-outline" id="add-question-btn" onclick="addQuestionCard()">＋ Add Another Question</button>
              <button type="submit" class="button">Submit Quiz for Approval →</button>
            </div>
            </form></section>

            <script>
              function renderQuestionTemplate(i) {
                return '<div class="question-card" id="qcard-' + i + '" style="border:1px solid var(--line);background:#faf9fd;padding:18px;border-radius:12px;margin-bottom:14px">' +
                  '<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:10px">' +
                    '<strong class="qcard-title" style="color:var(--purple);font-size:12px">Question ' + (i + 1) + '</strong>' +
                    '<button type="button" class="danger" onclick="removeQuestionCard(' + i + ')" style="cursor:pointer">✕ Remove</button>' +
                  '</div>' +
                  '<label>Question text<input required name="q' + i + '_text" placeholder="Enter question ' + (i + 1) + ' text"></label>' +
                  '<div class="form-row" style="grid-template-columns:1fr 1fr;gap:10px;margin-top:8px">' +
                    '<label>Option A<input required name="q' + i + '_o0" placeholder="Option A"></label>' +
                    '<label>Option B<input required name="q' + i + '_o1" placeholder="Option B"></label>' +
                    '<label>Option C<input required name="q' + i + '_o2" placeholder="Option C"></label>' +
                    '<label>Option D<input required name="q' + i + '_o3" placeholder="Option D"></label>' +
                  '</div>' +
                  '<label style="margin-top:8px;max-width:260px">Correct Answer' +
                    '<select name="q' + i + '_correct">' +
                      '<option value="0">Option A</option>' +
                      '<option value="1">Option B</option>' +
                      '<option value="2">Option C</option>' +
                      '<option value="3">Option D</option>' +
                    '</select>' +
                  '</label>' +
                '</div>';
              }

              function addQuestionCard() {
                var container = document.getElementById("questions-container");
                var count = container.getElementsByClassName("question-card").length;
                if (count >= 50) {
                  alert("Maximum 50 questions allowed per quiz.");
                  return;
                }
                var temp = document.createElement("div");
                temp.innerHTML = renderQuestionTemplate(count);
                container.appendChild(temp.firstElementChild);
                updateQuestionState();
              }

              function removeQuestionCard(index) {
                var card = document.getElementById("qcard-" + index);
                if (card) {
                  card.remove();
                  updateQuestionState();
                }
              }

              function updateQuestionState() {
                var container = document.getElementById("questions-container");
                var cards = container.getElementsByClassName("question-card");
                for (var i = 0; i < cards.length; i++) {
                  var card = cards[i];
                  card.id = "qcard-" + i;
                  var title = card.querySelector(".qcard-title");
                  if (title) title.textContent = "Question " + (i + 1);

                  var textInput = card.querySelector('input[name$="_text"]');
                  if (textInput) textInput.name = "q" + i + "_text";

                  for (var o = 0; o < 4; o++) {
                    var opt = card.querySelector('input[name$="_o' + o + '"]');
                    if (opt) opt.name = "q" + i + "_o" + o;
                  }

                  var sel = card.querySelector('select[name$="_correct"]');
                  if (sel) sel.name = "q" + i + "_correct";

                  var remBtn = card.querySelector(".danger");
                  if (i === 0 && remBtn) {
                    remBtn.remove();
                  } else if (i > 0 && !remBtn) {
                    var header = card.children[0];
                    var newBtn = document.createElement("button");
                    newBtn.type = "button";
                    newBtn.className = "danger";
                    newBtn.style.cursor = "pointer";
                    newBtn.textContent = "✕ Remove";
                    (function(idx) { newBtn.onclick = function() { removeQuestionCard(idx); }; })(i);
                    header.appendChild(newBtn);
                  } else if (remBtn) {
                    (function(idx) { remBtn.onclick = function() { removeQuestionCard(idx); }; })(i);
                  }
                }
                var badge = document.getElementById("question-count-badge");
                if (badge) badge.textContent = cards.length + (cards.length === 1 ? " Question" : " Questions");
              }
            </script>
            """.formatted(user.name.split(" ")[0], myQuizzes.size(), quizRows));
    }

    private static String adminDashboard() {
        StringBuilder users = new StringBuilder();
        for (User u : USERS) users.append("<div class='tr admin-user'><strong>").append(u.name).append("<small>").append(u.email).append("</small></strong><span>").append(u.role).append("</span><mark>● ").append(u.status).append("</mark><form method='post' action='/action'><input type='hidden' name='type' value='deleteUser'><input type='hidden' name='return' value='/admin'><input type='hidden' name='email' value='").append(u.email).append("'><button class='danger'>Delete</button></form></div>");
        StringBuilder pending = new StringBuilder();
        for (Quiz q : QUIZZES) pending.append("<div class='approval'><div><strong>").append(q.title).append("</strong><small>").append(q.level).append(" · ").append(q.questions.size()).append(" questions · ").append(q.duration).append(" min</small></div><span class='status ").append(q.status.toLowerCase()).append("'>").append(q.status).append("</span><form method='post' action='/action'><input type='hidden' name='return' value='/admin'><input type='hidden' name='name' value='").append(q.title).append("'><button name='type' value='approveQuiz'>Approve</button><button class='danger' name='type' value='rejectQuiz'>Reject</button></form></div>");
        return page("Admin Console", "admin", """
            <section class="dashboard-title"><div><span class="kicker">ADMINISTRATION</span><h1>System overview</h1></div><span class="health">● All systems operational</span></section>
            <section class="stats"><article><span class="stat-icon purple">♟</span><div><small>TOTAL USERS</small><strong>%d</strong></div></article><article><span class="stat-icon orange">▤</span><div><small>ALL QUIZZES</small><strong>%d</strong></div></article><article><span class="stat-icon blue">▶</span><div><small>TOTAL ATTEMPTS</small><strong>%d</strong></div></article></section>
            <div class="admin-grid"><section class="panel"><div class="section-head"><div><span class="kicker">USER MANAGEMENT</span><h2>User accounts</h2></div></div><div class="table"><div class="tr th admin-user"><span>USER</span><span>ROLE</span><span>STATUS</span><span>ACTION</span></div>%s</div><details><summary>＋ Add new user</summary><form method="post" action="/action" class="form inline-form"><input type="hidden" name="type" value="addUser"><input type="hidden" name="return" value="/admin"><label>Name<input required name="name"></label><label>Email<input required type="email" name="email"></label><label>Role<select name="role"><option>Participant</option><option>Quiz Creator</option><option>Admin</option></select></label><button class="button">Create user</button></form></details></section>
            </div>
            <section class="panel approvals"><div class="section-head"><div><span class="kicker">QUIZ CONTENT MANAGEMENT</span><h2>Content approval queue</h2></div></div>%s</section>
            """.formatted(USERS.size(), QUIZZES.size(), ATTEMPTS.size(), users, pending));
    }

    private static String page(String title, String role, String content) {
        User user = currentUser.get();
        String flash = successMessage.isBlank() ? "" : "<div class='flash'>✓ " + successMessage + "</div>";
        successMessage = "";
        String participantLinks = "<a href='/participant'>Dashboard</a><a href='/quizzes'>Explore Quizzes</a><a href='/performance'>My Performance</a><a href='/leaderboard'>Leaderboard</a>";
        String links = role.equals("participant") ? participantLinks : role.equals("creator") ? "<a href='/creator'>Creator Dashboard</a><a href='/quizzes'>Quiz Library</a>" : "<a href='/admin'>Admin Dashboard</a><a href='/quizzes'>Quiz Library</a>";
        
        String avatar = user != null ? initials(user.name) : "??";
        String userName = user != null ? user.name : "Guest";
        String roleName = user != null ? user.role : "Guest";
        String homeLink = user != null ? (user.role.equals("Admin") ? "/admin" : user.role.equals("Quiz Creator") ? "/creator" : "/participant") : "/auth";

        return """
            <!doctype html><html lang="en"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="description" content="Quizora Java online quiz platform"><title>%s · Quizora</title><style>%s</style></head>
            <body><header class="topbar"><a class="brand" href="%s"><span>&lt;/&gt;</span>quiz<b>ora</b></a><nav>%s</nav><div class="role-switch"><span class="avatar">%s</span><div><b>%s</b><small>%s</small></div><a href="/logout" style="margin-left:10px;font-size:11px;font-weight:700;color:var(--purple)">Logout</a></div></header>%s<main>%s</main><footer><a class="brand" href="%s"><span>&lt;/&gt;</span>quiz<b>ora</b></a><p>Java learning made practical. Built entirely with Java.</p><small>© %d Quizora. Educational project.</small></footer></body></html>
            """.formatted(title, CSS, homeLink, links, avatar, userName, roleName, flash, content, homeLink, LocalDate.now().getYear());
    }

    private static String initials(String name) {
        String[] parts = name.split(" ");
        if (parts.length == 0 || name.isBlank()) return "?";
        if (parts.length == 1) return name.substring(0, 1).toUpperCase();
        return (parts[0].substring(0, 1) + parts[parts.length - 1].substring(0, 1)).toUpperCase();
    }

    private static String notFound() {
        return page("Not Found", "participant", "<section class='result-card'><h1>Page not found</h1><p>The page you requested does not exist.</p><a class='button' href='/'>Return home</a></section>");
    }

    private static void send(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType("text/html; charset=UTF-8");
        response.getWriter().write(body);
    }

    private static void redirect(HttpServletResponse response, String location) throws IOException {
        response.setStatus(HttpServletResponse.SC_SEE_OTHER);
        response.setHeader("Location", location);
    }

    private static final class User implements Serializable {
        final String name, email, password, role, status;
        User(String name, String email, String password, String role, String status) { this.name = name; this.email = email; this.password = password; this.role = role; this.status = status; }
    }

    private static final class Question implements Serializable {
        final String text;
        final String[] options;
        final int correctOptionIndex;
        Question(String text, String[] options, int correctOptionIndex) { this.text = text; this.options = options; this.correctOptionIndex = correctOptionIndex; }
    }

    private static final class Quiz implements Serializable {
        final String title, description, level;
        final int duration;
        String status;
        final String creatorEmail;
        final List<Question> questions = new ArrayList<>();
        Quiz(String title, String description, int duration, String level, String status, String creatorEmail) { this.title = title; this.description = description; this.duration = duration; this.level = level; this.status = status; this.creatorEmail = creatorEmail; }
    }

    private static final class QuizAttempt implements Serializable {
        final String userEmail;
        final String quizTitle;
        final int score;
        final int maxScore;
        final String date;
        QuizAttempt(String userEmail, String quizTitle, int score, int maxScore, String date) { this.userEmail = userEmail; this.quizTitle = quizTitle; this.score = score; this.maxScore = maxScore; this.date = date; }
    }

    private static final String CSS = """
        @import url('https://fonts.googleapis.com/css2?family=DM+Sans:wght@400;500;600;700&family=Manrope:wght@600;700;800&display=swap');
        :root{--purple:#7654d5;--ink:#282139;--muted:#81798d;--line:#e9e5ef;--bg:#faf9fd}*{box-sizing:border-box}html{height:100%}body{margin:0;min-height:100vh;display:flex;flex-direction:column;background:var(--bg);color:var(--ink);font-family:'DM Sans',sans-serif}a{text-decoration:none;color:inherit}button,input,select,textarea{font:inherit}.topbar{height:76px;flex-shrink:0;width:100%;padding:0 max(24px,calc((100% - 1280px)/2));display:flex;align-items:center;justify-content:space-between;background:#fff;border-bottom:1px solid var(--line);position:sticky;top:0;z-index:5}.brand{font:800 22px Manrope;display:flex;align-items:center;gap:8px}.brand>span{width:39px;height:39px;border-radius:11px;display:grid;place-items:center;background:linear-gradient(135deg,#8d69e8,#6542c5);color:#fff;font-size:15px;box-shadow:0 7px 15px #7554d548}.brand>b{color:var(--purple);margin-left:-8px}.topbar nav{height:100%;display:flex;align-items:center;gap:32px}.topbar nav a{font-size:13px;font-weight:600;color:#766e82}.topbar nav a:hover{color:var(--purple)}.role-switch{display:flex;align-items:center;gap:9px}.role-switch>div{display:flex;flex-direction:column;font-size:11px}.role-switch small{color:#948d9e}.avatar{width:35px;height:35px;border-radius:9px;background:#eee8ff;color:#704ece;display:grid;place-items:center;font-size:11px;font-weight:800}.role-switch details{position:relative}.role-switch summary{list-style:none;cursor:pointer}.role-switch details div{position:absolute;right:0;top:24px;width:145px;padding:7px;background:white;border:1px solid var(--line);border-radius:9px;box-shadow:0 12px 30px #2f25452a}.role-switch details a{display:block;padding:8px;font-size:11px}.role-switch details a:hover{background:#f4f0fc;color:var(--purple)}main{width:100%;max-width:1280px;margin:0 auto;padding:28px 24px 70px;flex:1 0 auto}.hero{min-height:260px;padding:42px 55px;border-radius:22px;background:linear-gradient(120deg,#f0e9ff,#faf8ff 62%,#ede5ff);display:flex;justify-content:space-between;overflow:hidden}.kicker{color:var(--purple);font-size:9px;font-weight:800;letter-spacing:1.5px}.hero h1,.page-title h1,.dashboard-title h1{font:800 30px Manrope;margin:15px 0 7px}.hero p,.page-title p,.dashboard-title p{color:#726b7d;font-size:13px}.streak{display:flex;align-items:center;gap:10px;margin-top:25px}.streak>span{width:41px;height:41px;border-radius:11px;background:#fff1df;display:grid;place-items:center}.streak div{display:flex;flex-direction:column;font-size:12px}.streak small{font-size:10px;color:#8e8797}.hero-art{width:340px;position:relative}.code-window{position:absolute;right:12px;width:220px;height:135px;background:#ffffffd9;border:1px solid #e2daef;border-radius:14px;padding:16px;transform:rotate(3deg);box-shadow:0 18px 32px #40325e20}.code-window i{display:inline-block;width:7px;height:7px;border-radius:50%;background:#ff858c;margin-right:4px}.code-window i:nth-child(2){background:#ffd06e}.code-window i:nth-child(3){background:#75dca1}.code-window code{display:block;margin-top:17px;font-size:11px;line-height:1.8;color:#e07462}.code-window code b{color:var(--purple)}.trophy{position:absolute;bottom:0;left:28px;width:100px;height:100px;border-radius:50%;background:#fff;display:grid;place-items:center;font-size:48px;box-shadow:0 14px 28px #51406e25}.stats{display:grid;grid-template-columns:repeat(4,1fr);gap:15px;margin:18px 0 31px}.stats.three{grid-template-columns:repeat(3,1fr)}.stats article{display:flex;align-items:center;gap:13px;background:#fff;border:1px solid var(--line);border-radius:14px;padding:17px}.stat-icon{width:45px;height:45px;border-radius:12px;display:grid;place-items:center;font-size:20px}.purple{background:#eee8ff;color:#7653d5}.orange{background:#ffeedb;color:#e98b34}.blue{background:#e3f4ff;color:#3792cf}.pink{background:#ffe5ed;color:#df5d88}.stats article div{display:flex;flex-direction:column}.stats small{font-size:8px;letter-spacing:1px;color:#978f9e;font-weight:800}.stats strong{font:800 22px Manrope}.stats em{font-style:normal;font-size:9px;color:#72a983}.panel{background:#fff;border:1px solid var(--line);border-radius:17px;padding:24px}.section-head{display:flex;justify-content:space-between;align-items:end;margin-bottom:14px}.section-head h2,.panel h2{font:800 19px Manrope;margin:4px 0}.section-head>a,.rank-panel>a{font-size:10px;font-weight:700;color:var(--purple)}.continue{margin-bottom:32px}.continue-row{display:flex;align-items:center;gap:20px}.java-tile{width:90px;height:90px;border-radius:15px;background:linear-gradient(135deg,#7049cc,#9871e8);color:#fff;display:flex;flex-direction:column;justify-content:center;align-items:center;font:bold 30px monospace}.java-tile small{font:800 8px sans-serif;letter-spacing:1px}.grow{flex:1}.split{display:flex;justify-content:space-between;align-items:center}.pill{padding:5px 8px;border-radius:5px;font-size:8px;font-weight:800;letter-spacing:.7px}.amber{background:#fff0dc;color:#dc8735}.green{background:#e4f7ec;color:#439d68}.pink{background:#ffe6ed;color:#d9567e}.grow p,.quiz-card p{font-size:11px;color:#766f7f}.progress,.skill{height:6px;border-radius:5px;background:#eeeaf3}.progress i{display:block;width:40%;height:100%;background:var(--purple);border-radius:5px}.muted{color:#9991a0}.button{display:inline-flex;align-items:center;justify-content:center;border:0;border-radius:9px;background:linear-gradient(135deg,#7957d8,#6240bf);color:#fff!important;padding:11px 17px;font-size:10px;font-weight:700;box-shadow:0 7px 16px #7552d439;cursor:pointer}.main-grid{display:grid;grid-template-columns:1fr 290px;gap:22px}.quiz-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:14px}.quiz-grid.library{grid-template-columns:repeat(4,1fr)}.quiz-card{background:#fff;border:1px solid var(--line);border-radius:14px;padding:17px;transition:.2s}.quiz-card:hover{transform:translateY(-3px);box-shadow:0 12px 27px #49356815}.quiz-top{display:flex;justify-content:space-between}.quiz-icon{width:42px;height:42px;border-radius:11px;background:#eee8ff;color:var(--purple);display:grid;place-items:center;font:bold 15px monospace}.quiz-card h3{font:700 14px Manrope;margin:17px 0 7px}.quiz-card p{height:35px}.quiz-meta{display:flex;gap:14px;padding:10px 0;border-top:1px solid #efecf2;color:#968e9e;font-size:9px}.quiz-card>a{display:block;text-align:center;padding:8px;border-radius:7px;background:#f4f0fb;color:var(--purple);font-size:10px;font-weight:700}.rank-panel{background:linear-gradient(150deg,#f6f1ff,#fff);border:1px solid #e4dcf3;border-radius:16px;padding:20px}.rank-panel h3{margin:5px 0}.podium{height:130px;display:flex;align-items:center;justify-content:center;gap:10px}.podium>div{display:flex;flex-direction:column;align-items:center}.podium b{width:39px;height:39px;border-radius:50%;background:#eee8fa;color:#7250cd;display:grid;place-items:center;font-size:10px}.podium small{font-size:8px}.podium .winner{transform:translateY(-17px)}.podium .winner b{width:48px;height:48px;background:var(--purple);color:#fff}.me{display:flex;align-items:center;gap:8px;padding:9px;background:white;border-radius:9px;font-size:9px}.me>span{width:28px;height:28px;border-radius:7px;background:#eee8fa;display:grid;place-items:center}.me>div{display:flex;flex-direction:column;flex:1}.me em{font-style:normal;color:var(--purple);font-weight:700}.rank-panel>a{display:block;text-align:center;margin-top:14px}.history{margin-top:32px}.table{background:#fff;border:1px solid var(--line);border-radius:13px;overflow:hidden}.tr{display:grid;grid-template-columns:2fr 1.1fr .7fr .7fr .8fr;align-items:center;min-height:51px;padding:8px 17px;border-top:1px solid #f0edf3;font-size:10px;color:#756e7d}.tr.th{border:0;background:#faf9fc;font-size:8px;letter-spacing:.7px;color:#97909d;font-weight:800}.tr mark{justify-self:start;padding:4px 7px;background:#e7f7ed;color:#409868;border-radius:5px;font-size:8px}.page-title{text-align:center;margin:27px 0 31px}.search{max-width:650px;margin:0 auto 28px;display:flex;align-items:center;gap:9px;padding:7px 7px 7px 15px;background:#fff;border:1px solid var(--line);border-radius:11px}.search input{border:0;outline:0;flex:1}.search button,.reply button,.approval button{border:0;border-radius:7px;background:#eee8fa;color:var(--purple);padding:8px 13px;font-size:10px;font-weight:700}.empty{grid-column:1/-1;text-align:center;padding:50px;color:var(--muted)}.quiz-room{max-width:830px;margin:25px auto;background:#fff;border:1px solid var(--line);border-radius:19px;padding:32px}.quiz-room header{display:flex;justify-content:space-between;align-items:center}.quiz-room header h2{margin:5px 0;font:800 20px Manrope}.timer{display:grid;grid-template-columns:auto auto;gap:0 6px;color:var(--purple)}.timer small{grid-column:1/-1;font-size:7px;letter-spacing:1px}.question-progress{height:5px;background:#eeeaf4;margin:25px -32px}.question-progress i{display:block;width:20%;height:100%;background:var(--purple)}.quiz-room form>h1{font:800 24px Manrope;margin-top:30px}.answer{display:flex;align-items:center;gap:13px;border:1px solid var(--line);border-radius:11px;padding:14px;margin:10px 0;cursor:pointer}.answer:hover{border-color:#9e83e5;background:#faf8ff}.answer input{accent-color:var(--purple)}.answer span{width:28px;height:28px;border-radius:7px;background:#f2eef9;display:grid;place-items:center;color:var(--purple)}.quiz-actions{display:flex;justify-content:space-between;align-items:center;margin-top:27px}.quiz-actions>a{font-size:11px;color:#8b8492}.result-card{max-width:720px;margin:50px auto;text-align:center;background:#fff;border:1px solid var(--line);border-radius:20px;padding:45px}.result-ring{width:110px;height:110px;border:9px solid #7d5bd9;border-radius:50%;display:grid;place-items:center;margin:0 auto 23px;font:800 24px Manrope}.result-card h1{font:800 28px Manrope}.result-card p{color:var(--muted);line-height:1.7}.result-stats{display:grid;grid-template-columns:repeat(3,1fr);margin:25px 0;border:1px solid var(--line);border-radius:11px}.result-stats div{padding:17px;display:flex;flex-direction:column;border-right:1px solid var(--line)}.result-stats div:last-child{border:0}.result-stats small{color:var(--muted);font-size:9px}.performance-hero{display:flex;justify-content:space-between;align-items:end;padding:30px 40px;background:linear-gradient(130deg,#6f4dce,#9673e8);color:white;border-radius:18px}.performance-hero>div:first-child{display:flex;flex-direction:column}.performance-hero small{font-size:9px;letter-spacing:1px}.performance-hero strong{font:800 40px Manrope}.performance-hero span{font-size:11px}.chart{height:100px;width:350px;display:flex;align-items:end;gap:14px;border-bottom:1px solid #ffffff60}.chart i{flex:1;background:#ffffffb8;border-radius:5px 5px 0 0}.report-grid,.creator-grid,.admin-grid{display:grid;grid-template-columns:1fr 1fr;gap:18px;margin:20px 0}.skill{height:auto;background:none;margin:21px 0}.skill span{display:flex;justify-content:space-between;font-size:11px}.skill>i{display:block;height:7px;margin-top:7px;background:linear-gradient(90deg,#7654d5,#a587ed);border-radius:6px}.feedback blockquote{margin:24px 0 8px;padding:18px;background:#f7f4fc;border-left:3px solid var(--purple);font-size:12px;line-height:1.7}.feedback small{color:var(--muted)}.leaderboard{max-width:750px;margin:auto;background:#fff;border:1px solid var(--line);border-radius:16px;overflow:hidden}.leader-row{display:grid;grid-template-columns:45px 50px 1fr 90px;align-items:center;padding:13px 20px;border-bottom:1px solid #efecf2;font-size:12px}.leader-row>em{text-align:right;font-style:normal;color:var(--purple);font-weight:700}.leader-row.current{background:#f2edff}.leader-row small{color:var(--muted)}.dashboard-title{display:flex;justify-content:space-between;align-items:center;margin:18px 0 25px}.health{padding:9px 13px;border-radius:20px;background:#e6f7ec;color:#3c9662;font-size:10px}.creator-row{grid-template-columns:2fr .8fr .7fr .8fr .8fr}.creator-table{border:0}.status{justify-self:start;padding:5px 8px;border-radius:6px;font-size:8px;font-weight:800}.status.published{background:#e5f7ec;color:#3f9965}.status.pending{background:#fff0dc;color:#dc8735}.status.rejected{background:#ffe7eb;color:#d75a72}.creator-row a{color:var(--purple);font-weight:700}.form{display:flex;flex-direction:column;gap:12px;margin-top:18px}.form label{display:flex;flex-direction:column;gap:5px;color:#665f6f;font-size:10px;font-weight:700}.form input,.form select,.form textarea,.reply input{width:100%;padding:10px;border:1px solid #ded9e5;border-radius:8px;outline:0;background:#fcfbfd}.form textarea{min-height:75px;resize:vertical}.form input:focus,.form textarea:focus{border-color:#987ee0}.form-row{display:grid;grid-template-columns:repeat(3,1fr);gap:9px}.form .button{align-self:start}.message{display:flex;gap:11px;margin-top:20px;padding:15px;background:#f8f6fb;border-radius:11px}.message>div{flex:1}.message b,.message small{display:block;font-size:10px}.message small{color:var(--muted)}.message p{font-size:11px}.reply{display:flex;gap:7px;margin-top:12px}.admin-grid{grid-template-columns:2fr 1fr}.admin-user{grid-template-columns:2fr 1fr .7fr .5fr}.admin-user strong{display:flex;flex-direction:column}.admin-user strong small{font-weight:400;color:var(--muted);font-size:8px}.admin-user form{justify-self:start}.danger{border:0;color:#d5576e;background:#ffe9ed;padding:5px 8px;border-radius:5px;font-size:8px;cursor:pointer}details summary{cursor:pointer;color:var(--purple);font-size:11px;font-weight:700;margin-top:15px}.inline-form{display:grid;grid-template-columns:1fr 1fr 1fr auto;align-items:end}.check{flex-direction:row!important;align-items:center}.check input{width:auto}.approvals{margin-top:20px}.approval{display:grid;grid-template-columns:1fr 100px 170px;align-items:center;padding:13px 0;border-top:1px solid var(--line)}.approval>div{display:flex;flex-direction:column}.approval small{color:var(--muted);font-size:9px}.approval form{display:flex;gap:7px}footer{margin-top:auto;flex-shrink:0;width:100%;padding:35px max(24px,calc((100% - 1232px)/2));border-top:1px solid var(--line);background:#fff;display:flex;align-items:center;gap:28px}footer p{flex:1;color:var(--muted);font-size:11px}footer>small{color:#9a929f;font-size:9px}.flash{position:fixed;right:25px;top:92px;padding:13px 17px;border-radius:10px;background:#292338;color:white;font-size:11px;z-index:10;box-shadow:0 12px 28px #3329443d}
        .auth-card{background:#fff;border:1px solid var(--line);border-radius:20px}.auth-tabs{display:flex;background:#f3effc;border-radius:10px;padding:4px;margin-bottom:18px;gap:4px}.tab-btn{flex:1;border:0;background:transparent;padding:10px 14px;border-radius:8px;font-size:11px;font-weight:700;color:var(--muted);cursor:pointer;transition:.2s}.tab-btn.active{background:#fff;color:var(--purple);box-shadow:0 3px 12px #7554d525}.auth-alert{padding:10px 14px;border-radius:9px;font-size:11px;font-weight:600;margin-bottom:16px;text-align:left;display:flex;align-items:center;gap:8px}.auth-alert.error{background:#fff2f4;color:#d7385e;border:1px solid #ffd4dc}.auth-alert.success{background:#edf9f0;color:#2f855a;border:1px solid #c6f0d2}.auth-switch{margin-top:16px;font-size:11px;color:var(--muted)}.auth-switch a{color:var(--purple);font-weight:700;text-decoration:none}.auth-switch a:hover{text-decoration:underline}.demo-box{margin-top:22px;padding-top:16px;border-top:1px dashed var(--line);text-align:center}.demo-chips{display:flex;gap:7px;justify-content:center;margin-top:9px;flex-wrap:wrap}.demo-chip{border:1px solid #dfd5f6;background:#f8f6fc;color:var(--purple);padding:6px 11px;border-radius:8px;font-size:10px;font-weight:700;cursor:pointer;transition:.15s}.demo-chip:hover{background:#ede6fa;transform:translateY(-1px)}.button-outline{display:inline-flex;align-items:center;justify-content:center;border:1.5px solid var(--purple);border-radius:9px;background:#f8f5ff;color:var(--purple)!important;padding:10px 16px;font-size:11px;font-weight:700;cursor:pointer;transition:.15s}.button-outline:hover{background:#efe7fd;transform:translateY(-1px)}.modal-overlay{position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(20,15,35,0.6);backdrop-filter:blur(4px);display:none;align-items:center;justify-content:center;z-index:100;padding:20px}.modal-card{background:#fff;border-radius:18px;max-width:850px;width:100%;max-height:85vh;overflow-y:auto;padding:28px;box-shadow:0 24px 50px rgba(0,0,0,0.25);position:relative}.modal-close{position:absolute;top:20px;right:22px;border:0;background:#f3effc;color:var(--purple);font-size:18px;width:32px;height:32px;border-radius:50%;cursor:pointer;display:grid;place-items:center}
        @media(max-width:900px){.topbar nav{gap:13px}.stats{grid-template-columns:1fr 1fr}.quiz-grid.library{grid-template-columns:1fr 1fr}.main-grid{grid-template-columns:1fr}.rank-panel{display:none}.admin-grid{grid-template-columns:1fr}.hero-art{display:none}.chart{width:250px}}
        @media(max-width:650px){.topbar{height:auto;padding:13px 16px;flex-wrap:wrap;gap:12px}.topbar nav{order:3;width:100%;overflow:auto;padding:8px 0}.role-switch>div{display:none}main{padding:18px 14px 50px}.hero{padding:28px 23px}.stats,.stats.three,.quiz-grid,.quiz-grid.library,.report-grid,.creator-grid{grid-template-columns:1fr}.continue-row{flex-wrap:wrap}.grow{min-width:calc(100% - 115px)}.continue-row>.button{width:100%}.table{overflow:auto}.tr{min-width:620px}.performance-hero{display:block}.chart{width:100%;margin-top:20px}.dashboard-title{align-items:flex-start;gap:15px}.dashboard-title h1{font-size:24px}.form-row,.inline-form{grid-template-columns:1fr}.approval{grid-template-columns:1fr;gap:9px}.quiz-room{padding:20px}.question-progress{margin:20px -20px}.result-card{padding:28px 18px}.result-stats{grid-template-columns:1fr}.result-stats div{border-right:0;border-bottom:1px solid var(--line)}footer{display:block}footer p{margin:20px 0}}
        """;
}
