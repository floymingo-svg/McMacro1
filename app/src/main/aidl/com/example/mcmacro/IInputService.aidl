package com.example.mcmacro;

// Интерфейс Shizuku UserService (процесс с правами shell).
interface IInputService {
    // Обязательный метод Shizuku: код транзакции 16777114
    void destroy() = 16777114;

    // Один тап: DOWN -> пауза holdMs -> UP. oneway = не блокирует приложение,
    // порядок вызовов сохраняется.
    oneway void tap(float x, float y, int holdMs) = 1;

    // Имя тачскрина, найденного через getevent (пустая строка, пока не найден).
    String touchName() = 2;

    // Размер/поворот экрана и id InputDevice тачскрина (-1, если не определён).
    oneway void configure(int width, int height, int rotation, int deviceId) = 3;

    // Короткий статус для панели.
    String status() = 4;

    // Подробная диагностика (версия Android, пальцы, строки из logcat InputDispatcher).
    String diagnose() = 5;
}
