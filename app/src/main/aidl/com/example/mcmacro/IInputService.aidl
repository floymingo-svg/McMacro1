package com.example.mcmacro;

// Интерфейс Shizuku UserService (процесс с правами shell).
interface IInputService {
    // Обязательный метод Shizuku: код транзакции 16777114
    void destroy() = 16777114;

    // Один тап: DOWN -> пауза holdMs -> UP. oneway = не блокирует приложение,
    // порядок вызовов сохраняется.
    oneway void tap(float x, float y, int holdMs) = 1;
}
