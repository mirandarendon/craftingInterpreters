#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct Node {
    char *value;
    struct Node *prev;
    struct Node *next;
} Node;

// insert new node at the front of list
void insert(Node **head, const char *str) {
    Node *node = malloc(sizeof(Node));
    node->value = malloc(strlen(str) + 1);
    strcpy(node->value, str);
    node->prev = NULL;
    node->next = *head;

    // sets prev pointer of the old head node to the new node
    if (*head != NULL) {
        (*head)->prev = node;
    }
    *head = node;
}

// find node with given value
Node *find(Node *head, const char *str) {
    Node *node = head;
    while (node != NULL) {
        if (strcmp(node->value, str) == 0) {
            return node;
        }
        node = node->next;
    }
    return NULL;
}

// delete node with given value
void delete(Node **head, const char *str) {
    Node *node = find(*head, str);
    if (node == NULL) {
        return;
    }

    if (node->prev != NULL) {
        node->prev->next = node->next;
    } else {
        *head = node->next;
    }

    if (node->next != NULL) {
        node->next->prev = node->prev;
    }

    free(node->value);
    free(node);
}

// print all values in the list
void print(Node *head) {
    Node *node = head;
    while (node != NULL) {
        printf("%s ", node->value);
        node = node->next;
    }
    printf("\n");
}

int main(void) {
    Node *head = NULL;

    insert(&head, "one");
    insert(&head, "two");
    insert(&head, "three");
    print(head); // three two one

    delete(&head, "two");
    print(head); // three one

    return 0;
}
