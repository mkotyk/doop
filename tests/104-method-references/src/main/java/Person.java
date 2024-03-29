import java.time.LocalDate;
import java.util.*;

// Adapted from https://docs.oracle.com/javase/tutorial/java/javaOO/methodreferences.html
public class Person {

    public enum Sex {
        MALE, FEMALE
    }

    String name;
    int age;
    LocalDate birthday;
    Sex gender;
    String emailAddress;

    public int getAge() { return age; }
    public String getName() { return name; }

    public LocalDate getBirthday() {
        return birthday;
    }

    public void printPerson() { }

    public static int compareByAge(Person a, Person b) {
        return a.birthday.compareTo(b.birthday);
    }

    public static List<Person> createRoster() {
        List l = new ArrayList<>();
        Person p1 = new Person();
        p1.name = "A";
        p1.age = 1;
        l.add(p1);
        Person p2 = new Person();
        p2.name = "B";
        p2.age = 2;
        l.add(p2);
        return l;
    }
}
