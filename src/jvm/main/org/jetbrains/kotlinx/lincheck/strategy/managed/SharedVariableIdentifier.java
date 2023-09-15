/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed;

import java.util.WeakHashMap;

class SharedVariableIdentifier {
    private static final WeakHashMap<Object, Integer> ids = new WeakHashMap<>();
    private static int nextId = 0;

    static synchronized int getId(Object obj) {
        Integer id = ids.get(obj);
        if (id == null) {
            id = nextId++;
            ids.put(obj, id);
        }
        return id;
    }

    private SharedVariableIdentifier() { // non-instantiable
    }
}
