package borg.trikeshed.lib.collections

interface  Queue<T> {
     fun  add(e: T): Boolean
     fun  offer(e: T): Boolean
     fun  remove(): T
     fun  poll(): T?
     fun  element(): T
     fun  peek(): T?
}