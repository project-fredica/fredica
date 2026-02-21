import React, { useState } from 'react';
import { Columns, Filter, Group, SortAsc, MoreHorizontal, Search, Trash2, Check } from 'lucide-react';

interface ToolbarProps {
    onDeleteRow: () => void;
    hasSelection: boolean;
    visibleColumns: Record<string, boolean>;
    onToggleColumn: (key: string) => void;
}

export const Toolbar: React.FC<ToolbarProps> = ({
    onDeleteRow,
    hasSelection,
    visibleColumns,
    onToggleColumn
}) => {
    const [showFieldsMenu, setShowFieldsMenu] = useState(false);

    const columnLabels: Record<string, string> = {
        contactName: 'Contact Name',
        contactId: 'Contact ID',
        title: 'Title',
        profilePicture: 'Profile Picture',
        email: 'Email',
        subscription: 'Subscription',
        registrationNumber: 'Reg. Number',
        paidMember: 'Paid Member',
        postalAddress: 'Address'
    };

    return (
        <div className="h-12 border-b border-gray-200 bg-white flex items-center px-4 gap-4 text-xs font-medium text-gray-600 shrink-0 relative">
            <div className='lg:hidden ml-8 h-1'></div>
            <div className="relative">
                <button
                    onClick={() => setShowFieldsMenu(!showFieldsMenu)}
                    className={`flex items-center gap-1.5 hover:text-gray-900 px-2 py-1 rounded ${showFieldsMenu ? 'bg-gray-100' : ''}`}
                >
                    <Columns size={14} />
                    Fields
                </button>

                {showFieldsMenu && (
                    <>
                        <div className="fixed inset-0 z-20" onClick={() => setShowFieldsMenu(false)}></div>
                        <div className="absolute top-full left-0 mt-1 w-56 bg-white border border-gray-200 rounded-md shadow-lg z-30 p-1">
                            <div className="px-3 py-2 text-[10px] text-gray-400 uppercase font-bold border-b border-gray-100 mb-1">
                                Toggle Columns
                            </div>
                            {Object.entries(columnLabels).map(([key, label]) => (
                                <button
                                    key={key}
                                    onClick={() => onToggleColumn(key)}
                                    className="w-full flex items-center gap-2 px-3 py-1.5 hover:bg-gray-50 rounded text-left transition-colors"
                                >
                                    <div className={`w-3.5 h-3.5 border rounded-sm flex items-center justify-center transition-colors ${visibleColumns[key] ? 'bg-blue-600 border-blue-600' : 'bg-white border-gray-300'}`}>
                                        {visibleColumns[key] && <Check size={10} className="text-white" />}
                                    </div>
                                    <span className={visibleColumns[key] ? 'text-gray-800' : 'text-gray-400'}>{label}</span>
                                </button>
                            ))}
                        </div>
                    </>
                )}
            </div>

            <div className="h-4 w-[1px] bg-gray-200"></div>
            <button className="flex items-center gap-1.5 hover:text-gray-900">
                <Filter size={14} />
                Filter
            </button>
            <button className="flex items-center gap-1.5 hover:text-gray-900">
                <Group size={14} />
                Group
            </button>
            <button className="flex items-center gap-1.5 hover:text-gray-900">
                <SortAsc size={14} />
                Sort
            </button>

            {hasSelection && (
                <button
                    onClick={onDeleteRow}
                    className="flex items-center gap-1.5 text-red-600 hover:text-red-700 hover:bg-red-50 px-2 py-1 rounded transition-colors"
                >
                    <Trash2 size={14} />
                    Delete Row
                </button>
            )}

            <button className="p-1 hover:bg-gray-100 rounded">
                <MoreHorizontal size={14} />
            </button>

            <div className="flex-1"></div>

            <div className="relative">
                <Search size={14} className="absolute left-2 top-1/2 -translate-y-1/2 text-gray-400" />
                <input
                    type="text"
                    placeholder="Search Records..."
                    className="pl-8 pr-2 py-1 bg-gray-50 border border-gray-200 rounded-md focus:outline-none focus:ring-1 focus:ring-blue-500 w-48 text-xs"
                />
            </div>
        </div>
    );
};