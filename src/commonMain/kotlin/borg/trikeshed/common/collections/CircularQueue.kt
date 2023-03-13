package borg.trikeshed.common.collections

// Modify the CircularQueue class to accept an eviction delegate as a parameter
class CircularQueue<T>(capacity: Int, private val evictionDelegate: EvictionDelegate<T>?=null) {
  // The rest of the class definition is unchanged
// Define an interface for eviction delegates
  interface EvictionDelegate<T> {
      // A method that is called when an element is removed from the queue
      fun onEvict(element: T)
  }

  // Modify the dequeue method to call the eviction delegate's onEvict method
  fun dequeue(): T {
    if (isEmpty()) throw NoSuchElementException("Queue is empty")
    val element = array[head] as T // Get the element at the head index
    array[head] = null // Clear the slot
    head = (head +1) % array.size // Increment and wrap around if needed
    size--// Decreasethe size by one
    evictionDelegate?.onEvict(element) // Callthe onEvictmethodoftheevictiondelegate
    return element// Returnthe removed element
  } // The array that stores the elements
  private val array = arrayOfNulls<Any?>(capacity)
  // The index of the first element
  private var head = 0
  // The index of the next available slot
  private var tail = 0
  // The number of elements in the queue
  private var size = 0

  // Check if the queue is empty
  fun isEmpty(): Boolean {
    return size == 0
  }

  // Check if the queue is full
  fun isFull(): Boolean {
    return size == array.size
  }

  // Add an element to the tail of the queue
  fun enqueue(element: T) {
    if (isFull()) throw IllegalStateException("Queue is full")
    array[tail] = element // Store the element at the tail index
    tail = (tail + 1) % array.size // Increment and wrap around if needed
    size++ // Increase the size by one
  }



   companion object {
     // A factory method that creates a circular queue with a given capacity and initial elements
     fun <T> create(capacity: Int, vararg elements: T): CircularQueue<T> {
       val queue = CircularQueue<T>(capacity)// Createa new instance of CircularQueue<T> withthe given capacity
       for (element in elements) {// Loop throughthe given elements
         queue.enqueue(element)// Add each element tothe queue using enqueue() method
       }
       return queue// Returnthe createdqueue instance
     }
   }
}