package com.ichi2.libanki;

import java.util.Collection;
import java.util.List;

public class CollectionUtils {
    public static <T> T getLastListElement(List<T> l) {
        return l.get(l.size()-1);
    }
}
