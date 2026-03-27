interface Window {
    kmpJsBridge?: {
        callNative: (
            method: string,
            param: string,
            callback: (result: string) => void,
        ) => void;
    };
}
