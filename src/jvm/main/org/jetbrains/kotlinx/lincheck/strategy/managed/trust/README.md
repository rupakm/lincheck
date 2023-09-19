# TruSt Strategy

- [TruSt Strategy](#trust-strategy)
  - [Unique Identifier for Shared Variables](#unique-identifier-for-shared-variables)
  - [Consistency Checking](#consistency-checking)
  - [Optimization](#optimization)
  - [Context Switching](#context-switching)

## Unique Identifier for Shared Variables

The `ModelChecking` strategy which is used by default in Lincheck, doesn't need to 
distinguish between different shared variables. It is enough to know that there are
some shared variables, and they are accessed concurrently. In this case, the
strategy can check all possible interleaving of operations on these variables. 
In contrast, the `TruSt` strategy needs to distinguish between different shared
variables in order to build an execution graph. For this purpose, each shared
variable should have a unique identifier. However, it is not easy to find such
identifiers for all shared variables. Here we describe possible solutions for
this problem and their issues:

- **Use a stack to identify the variable by its path**  
  e.g. `testClass.c.val`. This solution has the following issues:
  - Building the path is very complicated because it requires to examine all 
    bytecode instructions that access the variable. For example, when a `getfield`
    instruction is met, we need to check that it is not a part of a method call
    (e.g. `testClass.c.val.toString()`). 
  - We need to check that the field is not accessed through a local variable 
    (e.g. `val c = testClass.c; c.val`).
  - How to deal with arrays?
  - How to handle the variables which are created inside the methods? What if
    the method is called in a loop? For example:
    ```java
    class TestClass {
        void bar() {
            ClassA a = new ClassA();
        }
        void foo() {
            for (int i = 0; i < 10; i++) {
                bar();
            }
        }
    }
    ```
  - What if the variable is changed? For example:
    ```java
    class TestClass {
        ClassA a = new ClassA();
        void foo() {
            a = new ClassA();
        }
    }
    ```
    They are different variables, but they have the same path.

- **Use the variables' memory addresses**
  This solution has the following issues:
  - It may not be possible to get the memory address of every variable. Some of them 
    are stored in registers, some of them are stored in the stack, and some of them
    are stored in the heap.
  - If we store the memory address as an Integer or String, then they will become 
    invalid after the garbage collection.
  - If we store the memory address as an Object, then garbage collection will not
    remove the object, and it will lead to memory leaks because the objects will
    always be reachable from the execution graph.
  - If we store the memory address as a WeakReference, then it will be removed by
    the garbage collection, but we will not be able to distinguish between different
    variables with the same memory address.
  - One possible solution would be to disable the garbage collection temporarily 
    while the execution graph is being built. However, it is not possible to do
    it in Java.

- **Generate a unique identifier for each variable based on their memory address**
  This solution may be the most possible one. However, we should be careful with
  the following issues:
  - If we use a `HashMap` to map each object to its identifier, we need to make sure 
    that the `hashCode` method is not overridden in the class of the object. Otherwise,
    we may face some issues as two different objects may have the same hash code.
  - We can use `IdentityHashMap` to avoid the previous issue as it uses `==` instead
    of `equals` to compare keys. However, we still have a problem with the garbage
    collection.
  - We can use `WeakHashMap` to avoid the garbage collection issue. But can we cast 
    primitive types to `Object` and use them as keys in the map without changing
    their memory addresses? *We should check `boxing` and `unboxing` in Java*.

## Consistency Checking

In order to check the consistency of the execution graph when adding an edge
(such as rf or co), we face the following questions:

- Is `Vector Clock` enough to check the consistency of the execution graph?
- How to check the consistency of the execution graph when adding an edge?
- What to do with the `Vector Clock` when revisiting a node?

## Optimization

For now, we don't have any optimization. A copy of the execution graph is made 
each time a revisit is performed. If we use the final version of the algorithm,
we would only have *one* execution graph, and revisits would be performed in-place (backtracking).

## Context Switching

Context switching (the `ManagedStrategy` API) has not been implemented yet.

## TODO

- [ ] Unique Identifier for Shared Variables are implemented using `WeakHashMap`. 
        We need to check if it is possible to use primitive types as keys in the map.
- [ ] Consistency Checking (checking if the algorithm is correct)
- [ ] Optimization (backtracking)
- [ ] Context Switching (the `ManagedStrategy` API)
- [ ] Fix bugs =)
